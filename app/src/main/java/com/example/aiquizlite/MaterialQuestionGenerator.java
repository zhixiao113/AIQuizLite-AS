package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public final class MaterialQuestionGenerator {
    public static final int MAX_REPAIR_ROUNDS = 3;
    private static final int ANALYSIS_SAMPLE_CHARACTERS = 6_000;
    private static final int DOCUMENT_BATCH_CHARACTERS = 30_000;
    private static final int QUESTION_BANK_CHUNK_OVERLAP = 800;
    private static final int MAX_TRUNCATION_SPLIT_DEPTH = 2;
    private static final int MIN_TRUNCATION_WINDOW_CHARACTERS = 1_024;
    private static final int MAX_SINGLE_GENERATION_QUESTIONS = 50;

    private final OpenAiCompatibleClient client;

    public MaterialQuestionGenerator() {
        this(new OpenAiCompatibleClient());
    }

    MaterialQuestionGenerator(OpenAiCompatibleClient client) {
        this.client = client;
    }

    public MaterialDocumentAnalysis analyzeDocument(
            ApiConfig config,
            String sourceName,
            List<MaterialTextRules.Chapter> chapters
    ) throws Exception {
        String response = client.createExtractionChatCompletion(
                config,
                "快速判断资料类型，只返回 documentKind、confidence、reason 三个 JSON 字段。",
                MaterialQuestionPromptBuilder.analyzeDocument(
                        sourceName,
                        MaterialTextRules.countCharacters(chapters),
                        chapters == null ? 0 : chapters.size(),
                        representativeSample(chapters)
                )
        );
        return MaterialDocumentAnalysis.fromModelText(response);
    }

    public MaterialGenerationPlan recommendLearningPlan(
            ApiConfig config,
            String bankName,
            String sourceName,
            List<MaterialTextRules.Chapter> chapters,
            RecognitionProgressListener listener
    ) throws Exception {
        List<List<MaterialTextRules.TextChunk>> batches = documentBatches(chapters, 0);
        if (batches.isEmpty()) {
            throw new IllegalArgumentException("资料中没有可分析的文字");
        }
        Map<String, MaterialTextRules.Chapter> chapterSources = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        for (MaterialTextRules.Chapter chapter : chapters) {
            if (chapter != null && !chapter.getContent().isEmpty()) {
                chapterSources.put(chapter.getTitle(), chapter);
                matrix.put(chapter.getTitle(), zeroTypes());
            }
        }
        for (int i = 0; i < batches.size(); i++) {
            List<MaterialTextRules.TextChunk> batch = batches.get(i);
            if (listener != null) {
                listener.onProgress(i, batches.size(), "正在批量盘点知识点");
            }
            String response = client.createLongChatCompletion(
                    config,
                    MaterialQuestionPromptBuilder.analysisSystemPrompt(),
                    MaterialQuestionPromptBuilder.analyzeLearningDocument(
                            sourceName,
                            MaterialQuestionPromptBuilder.documentSource(batch)
                    )
            );
            mergeLearningPlanResponse(response, matrix);
        }
        capMatrix(matrix, 100);
        Map<String, Integer> totals = zeroTypes();
        List<MaterialGenerationPlan.ChapterPlan> chapterPlans = new ArrayList<>();
        Map<String, Map<String, Integer>> selectedMatrix = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : matrix.entrySet()) {
            int count = sum(entry.getValue());
            if (count <= 0) {
                continue;
            }
            MaterialTextRules.Chapter source = chapterSources.get(entry.getKey());
            chapterPlans.add(new MaterialGenerationPlan.ChapterPlan(
                    entry.getKey(), source == null ? "" : source.getContent(), count
            ));
            selectedMatrix.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            for (Map.Entry<String, Integer> type : entry.getValue().entrySet()) {
                totals.put(type.getKey(), totals.get(type.getKey()) + type.getValue());
            }
        }
        if (chapterPlans.isEmpty()) {
            throw new IllegalArgumentException("AI 没有从资料中识别出可考查知识点");
        }
        if (listener != null) {
            listener.onProgress(batches.size(), batches.size(), "知识点与建议题量分析完成");
        }
        return new MaterialGenerationPlan(
                bankName, sourceName, chapterPlans, totals, selectedMatrix
        );
    }

    public QuestionBankConversionResult convertQuestionBank(
            ApiConfig config,
            String bankName,
            List<MaterialTextRules.Chapter> chapters,
            RecognitionProgressListener listener
    ) throws Exception {
        List<List<MaterialTextRules.TextChunk>> batches = documentBatches(
                chapters,
                QUESTION_BANK_CHUNK_OVERLAP
        );
        if (batches.isEmpty()) {
            throw new IllegalArgumentException("题库中没有可识别文字");
        }
        JSONArray pool = new JSONArray();
        ConversionProgress progress = new ConversionProgress(batches.size(), listener);
        progress.publish("正在识别原题");
        if (batches.size() == 1) {
            append(pool, convertBatch(config, bankName, batches.get(0), progress));
        } else {
            append(pool, convertBatchesConcurrently(config, bankName, batches, progress));
        }
        JSONArray candidates = new JSONArray();
        JSONArray unresolved = new JSONArray();
        for (int i = 0; i < pool.length(); i++) {
            JSONObject row = pool.getJSONObject(i);
            if (row.optBoolean("_unresolved", false)) unresolved.put(row);
            else candidates.put(row);
        }
        MaterialQuestionRules.ValidationResult validation =
                MaterialQuestionRules.validateDetected(candidates, bankName);
        if (validation.getAccepted().length() == 0) {
            if (unresolved.length() > 0) {
                List<Integer> numbers = new ArrayList<>();
                for (int i = 0; i < unresolved.length(); i++) {
                    numbers.add(unresolved.getJSONObject(i).optInt("numberInPaper", 0));
                }
                throw new IllegalArgumentException("原题全部需要人工校对，题号：" + numbers);
            }
            throw new IllegalArgumentException("AI 没有识别出答案完整、可转换的题目");
        }
        progress.publish("题库识别与格式校验完成");
        return new QuestionBankConversionResult(
                pool.length(),
                validation.getAccepted(),
                validation.getDuplicateCount(),
                validation.getInvalidCount(),
                unresolved
        );
    }

    private JSONArray convertBatch(ApiConfig config, String bankName,
            List<MaterialTextRules.TextChunk> batch, ConversionProgress progress) throws Exception {
        String source = MaterialQuestionPromptBuilder.questionBankSource(batch);
        return convertWindow(config, bankName, batch, source, 0, source.length(), 0, progress);
    }

    private JSONArray convertWindow(ApiConfig config, String bankName,
            List<MaterialTextRules.TextChunk> batch, String source, int start, int end,
            int depth, ConversionProgress progress) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("题库转换已停止");
        }
        try {
            String prompt = depth == 0
                    ? MaterialQuestionPromptBuilder.convertQuestionBankCompact(bankName, batch)
                    : MaterialQuestionPromptBuilder.convertQuestionBankWindow(bankName, source, start, end);
            String response = client.createExtractionChatCompletion(config,
                    "你是题库格式转换器，忠实提取已有题目、答案及原有解析，不重新解题。只返回 JSON。", prompt);
            JSONArray parsed = CompactQuestionBankCodec.decode(response, batch);
            attachDocumentMetadata(parsed, batch, bankName, "AI 识别原题");
            progress.complete();
            return parsed;
        } catch (OpenAiCompatibleClient.AiClientException error) {
            // Only an explicit provider length signal warrants extra paid requests.
            // Never retry auth, network, rate-limit or arbitrary malformed JSON automatically.
            if (!error.isOutputTruncated() || depth >= MAX_TRUNCATION_SPLIT_DEPTH
                    || end - start < MIN_TRUNCATION_WINDOW_CHARACTERS) {
                throw error;
            }
            int middle = splitWindowAt(source, start, end);
            progress.split();
            JSONArray result = convertWindow(config, bankName, batch, source,
                    start, middle, depth + 1, progress);
            append(result, convertWindow(config, bankName, batch, source,
                    middle, end, depth + 1, progress));
            return result;
        }
    }

    private static int splitWindowAt(String source, int start, int end) {
        int middle = start + (end - start) / 2;
        int newline = source.indexOf('\n', middle);
        if (newline >= middle && newline < end - (end - start) / 4) {
            return newline + 1;
        }
        // A long unbroken line may have no convenient boundary. Preserve UTF-16 pairs.
        if (Character.isHighSurrogate(source.charAt(middle - 1))
                && Character.isLowSurrogate(source.charAt(middle))) {
            middle++;
        }
        return middle;
    }

    private JSONArray convertBatchesConcurrently(ApiConfig config, String bankName,
            List<List<MaterialTextRules.TextChunk>> batches,
            ConversionProgress progress) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CompletionService<ConvertedBatch> completions = new ExecutorCompletionService<>(workers);
        List<Future<ConvertedBatch>> pending = new ArrayList<>();
        JSONArray[] ordered = new JSONArray[batches.size()];
        try {
            progress.publish("正在同时识别两批原题");
            // Submit two at a time so a failed wave cannot start the remaining document.
            for (int start = 0; start < batches.size(); start += 2) {
                int end = Math.min(start + 2, batches.size());
                pending.clear();
                for (int i = start; i < end; i++) {
                    final int index = i;
                    pending.add(completions.submit(() -> new ConvertedBatch(index,
                            convertBatch(config, bankName, batches.get(index), progress))));
                }
                for (int completed = start; completed < end; completed++) {
                    ConvertedBatch result = completions.take().get();
                    ordered[result.index] = result.questions;
                }
            }
            JSONArray pool = new JSONArray();
            for (JSONArray questions : ordered) {
                append(pool, questions);
            }
            return pool;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            for (Future<ConvertedBatch> future : pending) {
                future.cancel(true);
            }
            workers.shutdownNow();
        }
    }

    private static final class ConvertedBatch {
        final int index;
        final JSONArray questions;

        ConvertedBatch(int index, JSONArray questions) {
            this.index = index;
            this.questions = questions;
        }
    }

    private static final class ConversionProgress {
        private int completed;
        private int total;
        private final RecognitionProgressListener listener;

        ConversionProgress(int total, RecognitionProgressListener listener) {
            this.total = total;
            this.listener = listener;
        }

        synchronized void split() {
            total++;
            publish("返回内容过长，正在自动分段转换（额外请求会消耗 Token）");
        }

        synchronized void complete() {
            completed++;
            publish("已收到部分题目，正在整理结果");
        }

        synchronized void publish(String message) {
            if (listener != null) {
                listener.onProgress(completed, total, message);
            }
        }
    }

    public GenerationResult generate(
            ApiConfig config,
            MaterialGenerationPlan plan,
            JSONArray seedQuestions,
            ProgressListener listener
    ) throws Exception {
        JSONArray pool = copyArray(seedQuestions);
        int returnedCount = pool.length();
        if (pool.length() == 0) {
            Map<String, Map<String, Integer>> matrix = plan.allocateTypeMatrix();
            int completedCalls = 0;
            int estimatedCalls = estimateInitialRequests(plan);
            if (usesSingleGenerationRequest(plan)) {
                notify(listener, Snapshot.generating(
                        plan.getTargetCount(), 0, 1, 1, "正在批量生成完整题库"
                ));
                String response = client.createLongChatCompletion(
                        config,
                        MaterialQuestionPromptBuilder.systemPrompt(),
                        MaterialQuestionPromptBuilder.generateDocument(
                                plan.getBankName(),
                                planDocumentSource(plan),
                                matrix
                        )
                );
                JSONArray parsed = parseModelQuestions(response);
                attachPlanMetadata(parsed, plan, "批量生成");
                append(pool, parsed);
                returnedCount = parsed.length();
                completedCalls = 1;
            }
            if (completedCalls == 0) {
                for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
                    List<MaterialTextRules.TextChunk> chunks = sampledChunks(chapter);
                    List<Map<String, Integer>> chunkQuotas = allocateAcrossUnits(
                            matrix.get(chapter.getTitle()),
                            chunks.size()
                    );
                    for (int i = 0; i < chunks.size(); i++) {
                        Map<String, Integer> quota = chunkQuotas.get(i);
                        if (sum(quota) == 0) {
                            continue;
                        }
                        completedCalls++;
                        notify(listener, Snapshot.generating(
                                plan.getTargetCount(), returnedCount, completedCalls, estimatedCalls,
                                "正在生成 · " + chapter.getTitle()
                        ));
                        String response = client.createLongChatCompletion(
                                config,
                                MaterialQuestionPromptBuilder.systemPrompt(),
                                MaterialQuestionPromptBuilder.generate(
                                        plan.getBankName(),
                                        chapter.getTitle(),
                                        chunks.get(i).getText(),
                                        quota
                                )
                        );
                        JSONArray parsed = parseModelQuestions(response);
                        attachChapter(parsed, chapter.getTitle(), plan.getBankName(), "初次生成");
                        append(pool, parsed);
                        returnedCount += parsed.length();
                    }
                }
            }
        }

        PlanValidationResult validation = validatePlan(pool, plan);
        notify(listener, Snapshot.validating(plan, returnedCount, validation, 0, "首轮严格校验完成"));
        int rounds = 0;
        while (!validation.isComplete() && rounds < MAX_REPAIR_ROUNDS) {
            rounds++;
            JSONArray nextPool = copyArray(validation.getAccepted());
            String existingStems = existingStemList(validation.getAccepted());
            for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
                Map<String, Integer> missing = validation.getMissingByChapter().get(chapter.getTitle());
                if (missing == null || sum(missing) == 0) {
                    continue;
                }
                notify(listener, Snapshot.validating(
                        plan, returnedCount, validation, rounds,
                        "第 " + rounds + " 轮定向补齐 · " + chapter.getTitle()
                ));
                List<MaterialTextRules.TextChunk> chunks = MaterialTextRules.chunk(
                        Collections.singletonList(new MaterialTextRules.Chapter(
                                chapter.getTitle(), chapter.getText()
                        ))
                );
                String source = chunks.isEmpty()
                        ? chapter.getText()
                        : chunks.get((rounds - 1) % chunks.size()).getText();
                String response = client.createLongChatCompletion(
                        config,
                        MaterialQuestionPromptBuilder.systemPrompt(),
                        MaterialQuestionPromptBuilder.fillMissing(
                                plan.getBankName(),
                                chapter.getTitle(),
                                source,
                                missing,
                                existingStems
                        )
                );
                JSONArray parsed = parseModelQuestions(response);
                attachChapter(parsed, chapter.getTitle(), plan.getBankName(), "定向补充");
                append(nextPool, parsed);
                returnedCount += parsed.length();
            }
            pool = nextPool;
            validation = validatePlan(pool, plan);
            notify(listener, Snapshot.validating(
                    plan, returnedCount, validation, rounds,
                    validation.isComplete() ? "完整题库二次校验通过" : "补齐后重新校验"
            ));
        }
        PlanValidationResult finalValidation = validatePlan(validation.getAccepted(), plan);
        notify(listener, Snapshot.validating(
                plan, returnedCount, finalValidation, rounds,
                finalValidation.isComplete() ? "两轮严格校验通过" : "仍有题型缺口"
        ));
        return new GenerationResult(
                returnedCount,
                rounds,
                finalValidation,
                validation.getDuplicateCount(),
                validation.getInvalidCount()
        );
    }

    public JSONArray parsePastedWithAi(
            ApiConfig config,
            String pastedText,
            Map<String, Integer> missing
    ) throws Exception {
        String response = client.createChatCompletion(
                config,
                MaterialQuestionPromptBuilder.systemPrompt(),
                MaterialQuestionPromptBuilder.parsePasted(pastedText, missing)
        );
        return parseModelQuestions(response);
    }

    public JSONObject regenerateOne(
            ApiConfig config,
            MaterialGenerationPlan plan,
            String chapterTitle,
            String type,
            String oldStem
    ) throws Exception {
        MaterialGenerationPlan.ChapterPlan chapter = plan.findChapter(chapterTitle);
        if (chapter == null) {
            throw new IllegalArgumentException("找不到题目所属章节");
        }
        List<MaterialTextRules.TextChunk> chunks = MaterialTextRules.chunk(
                Collections.singletonList(new MaterialTextRules.Chapter(chapterTitle, chapter.getText()))
        );
        String source = chunks.isEmpty() ? chapter.getText() : chunks.get(0).getText();
        String response = client.createChatCompletion(
                config,
                MaterialQuestionPromptBuilder.systemPrompt(),
                MaterialQuestionPromptBuilder.regenerate(
                        plan.getBankName(), chapterTitle, source, type, oldStem
                )
        );
        JSONArray parsed = parseModelQuestions(response);
        if (parsed.length() == 0) {
            throw new IllegalArgumentException("AI 没有返回可用题目");
        }
        attachChapter(parsed, chapterTitle, plan.getBankName(), "重新生成");
        return parsed.getJSONObject(0);
    }

    public static PlanValidationResult validatePlan(
            JSONArray candidates,
            MaterialGenerationPlan plan
    ) throws JSONException {
        Map<String, Map<String, Integer>> matrix = plan.allocateTypeMatrix();
        Map<String, Map<String, Integer>> acceptedCounts = new LinkedHashMap<>();
        JSONArray accepted = new JSONArray();
        Set<String> globalStems = new HashSet<>();
        int duplicates = 0;
        int invalid = 0;
        int overflow = 0;

        for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
            JSONArray chapterCandidates = new JSONArray();
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject item = candidates.optJSONObject(i);
                if (item != null && chapter.getTitle().equals(item.optString("sourceChapter", ""))) {
                    chapterCandidates.put(item);
                }
            }
            MaterialQuestionRules.ValidationResult chapterResult = MaterialQuestionRules.validate(
                    chapterCandidates,
                    matrix.get(chapter.getTitle()),
                    plan.getBankName()
            );
            invalid += chapterResult.getInvalidCount();
            duplicates += chapterResult.getDuplicateCount();
            overflow += chapterResult.getOverflowCount();
            Map<String, Integer> counts = zeroTypes();
            for (int i = 0; i < chapterResult.getAccepted().length(); i++) {
                JSONObject item = chapterResult.getAccepted().getJSONObject(i);
                String fingerprint = MaterialQuestionRules.stemFingerprint(item.optString("stem", ""));
                if (!globalStems.add(fingerprint)) {
                    duplicates++;
                    continue;
                }
                String type = item.getString("type");
                counts.put(type, counts.get(type) + 1);
                item.put("sourceChapter", chapter.getTitle());
                accepted.put(item);
            }
            acceptedCounts.put(chapter.getTitle(), counts);
        }

        Map<String, Map<String, Integer>> missing = new LinkedHashMap<>();
        for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
            Map<String, Integer> row = new LinkedHashMap<>();
            Map<String, Integer> targetRow = matrix.get(chapter.getTitle());
            Map<String, Integer> actualRow = acceptedCounts.get(chapter.getTitle());
            for (String type : targetRow.keySet()) {
                row.put(type, Math.max(0, targetRow.get(type) - actualRow.get(type)));
            }
            missing.put(chapter.getTitle(), Collections.unmodifiableMap(row));
        }
        reindex(accepted, plan.getBankName());
        return new PlanValidationResult(
                accepted,
                Collections.unmodifiableMap(acceptedCounts),
                Collections.unmodifiableMap(missing),
                duplicates,
                invalid,
                overflow,
                candidates.length(),
                plan.getTargetCount()
        );
    }

    public static JSONArray parseModelQuestions(String response) throws Exception {
        return QuestionRepository.normalizeImportedJson(response);
    }

    public static int estimateInitialRequests(MaterialGenerationPlan plan) {
        if (usesSingleGenerationRequest(plan)) {
            return 1;
        }
        int count = 0;
        for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
            count += sampledChunks(chapter).size();
        }
        return Math.max(1, count);
    }

    public static int estimateQuestionBankRequests(List<MaterialTextRules.Chapter> chapters) {
        return Math.max(1, documentBatches(chapters, QUESTION_BANK_CHUNK_OVERLAP).size());
    }

    public static int estimateLearningPlanRequests(List<MaterialTextRules.Chapter> chapters) {
        return Math.max(1, documentBatches(chapters, 0).size());
    }

    private static List<List<MaterialTextRules.TextChunk>> documentBatches(
            List<MaterialTextRules.Chapter> chapters,
            int overlapCharacters
    ) {
        List<MaterialTextRules.TextChunk> atomicChunks = new ArrayList<>();
        if (chapters != null) {
            for (MaterialTextRules.Chapter chapter : chapters) {
                if (chapter == null || chapter.getContent().isEmpty()) {
                    continue;
                }
                atomicChunks.addAll(MaterialTextRules.chunkWithOverlap(
                        Collections.singletonList(chapter),
                        DOCUMENT_BATCH_CHARACTERS,
                        overlapCharacters
                ));
            }
        }
        List<List<MaterialTextRules.TextChunk>> batches = new ArrayList<>();
        List<MaterialTextRules.TextChunk> current = new ArrayList<>();
        int currentCharacters = 0;
        for (MaterialTextRules.TextChunk chunk : atomicChunks) {
            int addition = chunk.getText().length() + chunk.getChapterTitle().length() + 8;
            if (!current.isEmpty() && currentCharacters + addition > DOCUMENT_BATCH_CHARACTERS) {
                batches.add(Collections.unmodifiableList(new ArrayList<>(current)));
                current.clear();
                currentCharacters = 0;
            }
            current.add(chunk);
            currentCharacters += addition;
        }
        if (!current.isEmpty()) {
            batches.add(Collections.unmodifiableList(new ArrayList<>(current)));
        }
        return Collections.unmodifiableList(batches);
    }

    private static void mergeLearningPlanResponse(
            String response,
            Map<String, Map<String, Integer>> matrix
    ) throws Exception {
        String value = response == null ? "" : response.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("AI 没有返回知识点规划结果");
        }
        JSONObject root = new JSONObject(value.substring(start, end + 1));
        JSONArray chapters = root.optJSONArray("chapters");
        int accepted = 0;
        if (chapters != null) {
            for (int i = 0; i < chapters.length(); i++) {
                JSONObject item = chapters.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String title = matchChapterTitle(item.optString("title", ""), matrix.keySet());
                JSONObject counts = item.optJSONObject("typeCounts");
                if (title == null || counts == null) {
                    continue;
                }
                Map<String, Integer> row = matrix.get(title);
                java.util.Iterator<String> keys = counts.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String type = MaterialQuestionRules.normalizeType(key);
                    int count = Math.max(0, counts.optInt(key, 0));
                    if (type != null && count > 0) {
                        row.put(type, row.get(type) + count);
                        accepted += count;
                    }
                }
            }
        }
        if (accepted == 0) {
            throw new IllegalArgumentException("AI 没有从资料中识别出可考查知识点");
        }
    }

    private static boolean usesSingleGenerationRequest(MaterialGenerationPlan plan) {
        if (plan == null || plan.getTargetCount() > MAX_SINGLE_GENERATION_QUESTIONS) {
            return false;
        }
        int characters = 0;
        for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
            characters += chapter.getText().length() + chapter.getTitle().length() + 8;
        }
        return characters > 0 && characters <= DOCUMENT_BATCH_CHARACTERS;
    }

    private static String planDocumentSource(MaterialGenerationPlan plan) {
        List<MaterialTextRules.TextChunk> chunks = new ArrayList<>();
        for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
            chunks.add(new MaterialTextRules.TextChunk(chapter.getTitle(), chapter.getText()));
        }
        return MaterialQuestionPromptBuilder.documentSource(chunks);
    }

    private static void attachPlanMetadata(
            JSONArray questions,
            MaterialGenerationPlan plan,
            String qualityState
    ) throws Exception {
        List<MaterialTextRules.TextChunk> sources = new ArrayList<>();
        for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
            sources.add(new MaterialTextRules.TextChunk(chapter.getTitle(), chapter.getText()));
        }
        attachDocumentMetadata(questions, sources, plan.getBankName(), qualityState);
    }

    private static void attachDocumentMetadata(
            JSONArray questions,
            List<MaterialTextRules.TextChunk> sources,
            String paper,
            String qualityState
    ) throws Exception {
        Set<String> titles = new HashSet<>();
        for (MaterialTextRules.TextChunk source : sources) {
            titles.add(source.getChapterTitle());
        }
        String fallback = titles.size() == 1 ? titles.iterator().next() : "全文";
        for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String matched = matchChapterTitle(item.optString("sourceChapter", ""), titles);
            if (matched == null) {
                matched = matchChapterBySnippet(item.optString("sourceSnippet", ""), sources);
            }
            item.put("sourceChapter", matched == null ? fallback : matched);
            item.put("paper", paper);
            item.put("qualityState", qualityState);
        }
    }

    private static String matchChapterBySnippet(
            String candidate,
            List<MaterialTextRules.TextChunk> sources
    ) {
        String snippet = candidate == null ? "" : candidate.trim();
        if (snippet.length() < 4) {
            return null;
        }
        String matched = null;
        for (MaterialTextRules.TextChunk source : sources) {
            if (!source.getText().contains(snippet)) {
                continue;
            }
            if (matched != null && !matched.equals(source.getChapterTitle())) {
                return null;
            }
            matched = source.getChapterTitle();
        }
        return matched;
    }

    private static String matchChapterTitle(String candidate, Set<String> titles) {
        String value = normalizeChapterTitle(candidate);
        if (value.isEmpty()) {
            return null;
        }
        for (String title : titles) {
            if (normalizeChapterTitle(title).equals(value)) {
                return title;
            }
        }
        for (String title : titles) {
            String normalized = normalizeChapterTitle(title);
            if (value.contains(normalized) || normalized.contains(value)) {
                return title;
            }
        }
        return null;
    }

    private static String normalizeChapterTitle(String value) {
        return value == null
                ? ""
                : value.replace("【", "")
                        .replace("】", "")
                        .replace("章节：", "")
                        .replaceAll("\\s+", "")
                        .trim();
    }

    private static void capMatrix(Map<String, Map<String, Integer>> matrix, int maximum) {
        int total = 0;
        for (Map<String, Integer> row : matrix.values()) {
            total += sum(row);
        }
        while (total > maximum) {
            Map<String, Integer> largestRow = null;
            String largestType = null;
            int largest = 0;
            for (Map<String, Integer> row : matrix.values()) {
                for (Map.Entry<String, Integer> entry : row.entrySet()) {
                    if (entry.getValue() > largest) {
                        largest = entry.getValue();
                        largestRow = row;
                        largestType = entry.getKey();
                    }
                }
            }
            if (largestRow == null || largest <= 0) {
                break;
            }
            largestRow.put(largestType, largest - 1);
            total--;
        }
    }

    private static String representativeSample(List<MaterialTextRules.Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return "";
        }
        List<MaterialTextRules.Chapter> usable = new ArrayList<>();
        for (MaterialTextRules.Chapter chapter : chapters) {
            if (chapter != null && !chapter.getContent().isEmpty()) {
                usable.add(chapter);
            }
        }
        int sampleCount = Math.min(12, usable.size());
        if (sampleCount == 0) {
            return "";
        }
        int perChapter = ANALYSIS_SAMPLE_CHARACTERS / sampleCount;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < sampleCount; i++) {
            int index = sampleCount == 1 ? 0
                    : (int) ((long) i * (usable.size() - 1) / (sampleCount - 1));
            MaterialTextRules.Chapter chapter = usable.get(index);
            String title = chapter.getTitle();
            title = title.substring(0, Math.min(title.length(), 80));
            int allowance = Math.max(1, perChapter - title.length() - 9);
            String text = chapter.getContent();
            String sample;
            if (text.length() <= allowance) {
                sample = text;
            } else {
                int head = allowance / 2;
                int tail = allowance - head;
                sample = text.substring(0, head) + "\n…\n" + text.substring(text.length() - tail);
            }
            if (result.length() > 0) {
                result.append("\n\n");
            }
            result.append("【").append(title).append("】\n").append(sample);
        }
        return result.toString();
    }

    private static List<MaterialTextRules.TextChunk> sampledChunks(
            MaterialGenerationPlan.ChapterPlan chapter
    ) {
        List<MaterialTextRules.TextChunk> chunks = MaterialTextRules.chunk(
                Collections.singletonList(new MaterialTextRules.Chapter(
                        chapter.getTitle(), chapter.getText()
                )),
                DOCUMENT_BATCH_CHARACTERS
        );
        int maximum = Math.max(1, chapter.getQuestionCount());
        if (chunks.size() <= maximum) {
            return chunks;
        }
        List<MaterialTextRules.TextChunk> sampled = new ArrayList<>();
        for (int i = 0; i < maximum; i++) {
            int index = maximum == 1
                    ? chunks.size() / 2
                    : Math.round(i * (chunks.size() - 1f) / (maximum - 1f));
            sampled.add(chunks.get(index));
        }
        return sampled;
    }

    private static List<Map<String, Integer>> allocateAcrossUnits(
            Map<String, Integer> quotas,
            int unitCount
    ) {
        List<Map<String, Integer>> result = new ArrayList<>();
        if (unitCount <= 0) {
            return result;
        }
        Map<String, Integer> remaining = new LinkedHashMap<>(quotas);
        int remainingTotal = sum(remaining);
        for (int unit = 0; unit < unitCount; unit++) {
            int unitsLeft = unitCount - unit;
            int target = remainingTotal / unitsLeft + (remainingTotal % unitsLeft > 0 ? 1 : 0);
            Map<String, Integer> row = zeroTypes();
            int allocated = 0;
            while (allocated < target) {
                String best = null;
                int available = -1;
                for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
                    int value = entry.getValue() - row.get(entry.getKey());
                    if (value > available) {
                        best = entry.getKey();
                        available = value;
                    }
                }
                if (best == null || available <= 0) {
                    break;
                }
                row.put(best, row.get(best) + 1);
                allocated++;
            }
            for (String type : remaining.keySet()) {
                remaining.put(type, remaining.get(type) - row.get(type));
            }
            remainingTotal -= allocated;
            result.add(Collections.unmodifiableMap(row));
        }
        return result;
    }

    private static void attachChapter(
            JSONArray questions,
            String chapter,
            String paper,
            String qualityState
    ) throws JSONException {
        for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.optJSONObject(i);
            if (item != null) {
                item.put("sourceChapter", chapter);
                item.put("paper", paper);
                item.put("qualityState", qualityState);
            }
        }
    }

    private static void reindex(JSONArray questions, String paper) throws JSONException {
        for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.getJSONObject(i);
            item.put("id", "material_q" + (i + 1));
            item.put("index", i + 1);
            item.put("numberInPaper", i + 1);
            item.put("paper", paper);
        }
    }

    private static String existingStemList(JSONArray questions) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(i + 1).append(". ").append(item.optString("stem", ""));
        }
        return result.toString();
    }

    private static Map<String, Integer> zeroTypes() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put(MaterialQuestionRules.SINGLE, 0);
        result.put(MaterialQuestionRules.MULTI, 0);
        result.put(MaterialQuestionRules.JUDGE, 0);
        result.put(MaterialQuestionRules.FILL_BLANK, 0);
        result.put(MaterialQuestionRules.MATCHING, 0);
        return result;
    }

    private static int sum(Map<String, Integer> values) {
        int total = 0;
        if (values != null) {
            for (int value : values.values()) {
                total += value;
            }
        }
        return total;
    }

    private static JSONArray copyArray(JSONArray source) throws JSONException {
        if (source == null) {
            return new JSONArray();
        }
        return new JSONArray(source.toString());
    }

    private static void append(JSONArray target, JSONArray values) {
        for (int i = 0; i < values.length(); i++) {
            target.put(values.opt(i));
        }
    }

    private static void notify(ProgressListener listener, Snapshot snapshot) {
        if (listener != null) {
            listener.onProgress(snapshot);
        }
    }

    public interface ProgressListener {
        void onProgress(Snapshot snapshot);
    }

    public interface RecognitionProgressListener {
        void onProgress(int completedRequests, int totalRequests, String message);
    }

    public static final class QuestionBankConversionResult {
        private final int returnedCount;
        private final JSONArray accepted;
        private final int duplicateCount;
        private final int invalidCount;
        private final JSONArray unresolved;

        QuestionBankConversionResult(
                int returnedCount,
                JSONArray accepted,
                int duplicateCount,
                int invalidCount,
                JSONArray unresolved
        ) {
            this.returnedCount = returnedCount;
            this.accepted = accepted;
            this.duplicateCount = duplicateCount;
            this.invalidCount = invalidCount;
            this.unresolved = unresolved;
        }

        public int getReturnedCount() {
            return returnedCount;
        }

        public JSONArray getAccepted() {
            return accepted;
        }

        public int getDuplicateCount() {
            return duplicateCount;
        }

        public int getInvalidCount() {
            return invalidCount;
        }

        public JSONArray getUnresolved() { return unresolved; }
    }

    public static final class Snapshot {
        public final int target;
        public final int returned;
        public final int valid;
        public final int duplicates;
        public final int invalid;
        public final int missing;
        public final int repairRound;
        public final String message;
        public final int completedRequests;
        public final int estimatedRequests;
        private final JSONArray checkpoint;

        private Snapshot(
                int target,
                int returned,
                int valid,
                int duplicates,
                int invalid,
                int missing,
                int repairRound,
                String message,
                int completedRequests,
                int estimatedRequests,
                JSONArray checkpoint
        ) {
            this.target = target;
            this.returned = returned;
            this.valid = valid;
            this.duplicates = duplicates;
            this.invalid = invalid;
            this.missing = missing;
            this.repairRound = repairRound;
            this.message = message;
            this.completedRequests = completedRequests;
            this.estimatedRequests = estimatedRequests;
            this.checkpoint = checkpoint;
        }

        static Snapshot generating(
                int target,
                int returned,
                int completed,
                int estimated,
                String message
        ) {
            return new Snapshot(target, returned, 0, 0, 0, target, 0, message, completed, estimated, null);
        }

        static Snapshot validating(
                MaterialGenerationPlan plan,
                int returned,
                PlanValidationResult validation,
                int round,
                String message
        ) {
            return new Snapshot(
                    plan.getTargetCount(), returned, validation.getAccepted().length(),
                    validation.getDuplicateCount(), validation.getInvalidCount(),
                    validation.getMissingCount(), round, message, 0, 0, validation.getAccepted()
            );
        }

        public JSONArray getCheckpoint() {
            return checkpoint;
        }
    }

    public static final class GenerationResult {
        private final int returnedCount;
        private final int repairRounds;
        private final PlanValidationResult validation;
        private final int removedDuplicateCount;
        private final int removedInvalidCount;

        GenerationResult(int returnedCount, int repairRounds, PlanValidationResult validation) {
            this(
                    returnedCount,
                    repairRounds,
                    validation,
                    validation.getDuplicateCount(),
                    validation.getInvalidCount()
            );
        }

        GenerationResult(
                int returnedCount,
                int repairRounds,
                PlanValidationResult validation,
                int removedDuplicateCount,
                int removedInvalidCount
        ) {
            this.returnedCount = returnedCount;
            this.repairRounds = repairRounds;
            this.validation = validation;
            this.removedDuplicateCount = removedDuplicateCount;
            this.removedInvalidCount = removedInvalidCount;
        }

        public int getReturnedCount() {
            return returnedCount;
        }

        public int getRepairRounds() {
            return repairRounds;
        }

        public PlanValidationResult getValidation() {
            return validation;
        }

        public int getRemovedDuplicateCount() {
            return removedDuplicateCount;
        }

        public int getRemovedInvalidCount() {
            return removedInvalidCount;
        }
    }

    public static final class PlanValidationResult {
        private final JSONArray accepted;
        private final Map<String, Map<String, Integer>> acceptedByChapter;
        private final Map<String, Map<String, Integer>> missingByChapter;
        private final int duplicateCount;
        private final int invalidCount;
        private final int overflowCount;
        private final int candidateCount;
        private final int targetCount;

        PlanValidationResult(
                JSONArray accepted,
                Map<String, Map<String, Integer>> acceptedByChapter,
                Map<String, Map<String, Integer>> missingByChapter,
                int duplicateCount,
                int invalidCount,
                int overflowCount,
                int candidateCount,
                int targetCount
        ) {
            this.accepted = accepted;
            this.acceptedByChapter = acceptedByChapter;
            this.missingByChapter = missingByChapter;
            this.duplicateCount = duplicateCount;
            this.invalidCount = invalidCount;
            this.overflowCount = overflowCount;
            this.candidateCount = candidateCount;
            this.targetCount = targetCount;
        }

        public JSONArray getAccepted() {
            return accepted;
        }

        public Map<String, Map<String, Integer>> getAcceptedByChapter() {
            return acceptedByChapter;
        }

        public Map<String, Map<String, Integer>> getMissingByChapter() {
            return missingByChapter;
        }

        public int getDuplicateCount() {
            return duplicateCount;
        }

        public int getInvalidCount() {
            return invalidCount;
        }

        public int getOverflowCount() {
            return overflowCount;
        }

        public int getCandidateCount() {
            return candidateCount;
        }

        public int getMissingCount() {
            return Math.max(0, targetCount - accepted.length());
        }

        public boolean isComplete() {
            return accepted.length() == targetCount && getMissingCount() == 0;
        }

        public Map<String, Integer> aggregateMissingByType() {
            Map<String, Integer> result = zeroTypes();
            for (Map<String, Integer> row : missingByChapter.values()) {
                for (Map.Entry<String, Integer> entry : row.entrySet()) {
                    result.put(entry.getKey(), result.get(entry.getKey()) + entry.getValue());
                }
            }
            return result;
        }
    }
}
