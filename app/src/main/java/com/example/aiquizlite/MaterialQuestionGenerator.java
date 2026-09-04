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

public final class MaterialQuestionGenerator {
    public static final int MAX_REPAIR_ROUNDS = 3;
    private static final int ANALYSIS_SAMPLE_CHARACTERS = 24_000;
    private static final int QUESTION_BANK_CHUNK_CHARACTERS = 10_000;
    private static final int QUESTION_BANK_CHUNK_OVERLAP = 1_000;

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
        String response = client.createChatCompletion(
                config,
                MaterialQuestionPromptBuilder.analysisSystemPrompt(),
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
        List<MaterialTextRules.TextChunk> chunks = MaterialTextRules.chunk(chapters);
        if (chunks.isEmpty()) {
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
        for (int i = 0; i < chunks.size(); i++) {
            MaterialTextRules.TextChunk chunk = chunks.get(i);
            if (listener != null) {
                listener.onProgress(i, chunks.size(), "正在盘点知识点 · " + chunk.getChapterTitle());
            }
            String response = client.createChatCompletion(
                    config,
                    MaterialQuestionPromptBuilder.analysisSystemPrompt(),
                    MaterialQuestionPromptBuilder.analyzeLearningChunk(
                            sourceName,
                            chunk.getChapterTitle(),
                            chunk.getText()
                    )
            );
            MaterialDocumentAnalysis analysis = MaterialDocumentAnalysis.fromModelText(response);
            Map<String, Integer> row = matrix.get(chunk.getChapterTitle());
            if (row == null) {
                row = zeroTypes();
                matrix.put(chunk.getChapterTitle(), row);
            }
            for (Map.Entry<String, Integer> entry : analysis.getTypeQuotas().entrySet()) {
                row.put(entry.getKey(), row.get(entry.getKey()) + entry.getValue());
            }
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
            listener.onProgress(chunks.size(), chunks.size(), "知识点与建议题量分析完成");
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
        List<MaterialTextRules.TextChunk> chunks = MaterialTextRules.chunkWithOverlap(
                chapters,
                QUESTION_BANK_CHUNK_CHARACTERS,
                QUESTION_BANK_CHUNK_OVERLAP
        );
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("题库中没有可识别文字");
        }
        JSONArray pool = new JSONArray();
        for (int i = 0; i < chunks.size(); i++) {
            MaterialTextRules.TextChunk chunk = chunks.get(i);
            if (listener != null) {
                listener.onProgress(i, chunks.size(), "正在识别 · " + chunk.getChapterTitle());
            }
            String response = client.createChatCompletion(
                    config,
                    MaterialQuestionPromptBuilder.systemPrompt(),
                    MaterialQuestionPromptBuilder.convertQuestionBankChunk(
                            bankName,
                            chunk.getChapterTitle(),
                            chunk.getText()
                    )
            );
            JSONArray parsed = parseModelQuestions(response);
            attachChapter(parsed, chunk.getChapterTitle(), bankName, "AI 识别原题");
            append(pool, parsed);
        }
        MaterialQuestionRules.ValidationResult validation =
                MaterialQuestionRules.validateDetected(pool, bankName);
        if (validation.getAccepted().length() == 0) {
            throw new IllegalArgumentException("AI 没有识别出答案完整、可转换的题目");
        }
        if (listener != null) {
            listener.onProgress(chunks.size(), chunks.size(), "题库识别与格式校验完成");
        }
        return new QuestionBankConversionResult(
                pool.length(),
                validation.getAccepted(),
                validation.getDuplicateCount(),
                validation.getInvalidCount()
        );
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
            for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
                List<MaterialTextRules.TextChunk> chunks = sampledChunks(chapter);
                List<Map<String, Integer>> chunkQuotas = allocateAcrossUnits(matrix.get(chapter.getTitle()), chunks.size());
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
                    String response = client.createChatCompletion(
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
                String response = client.createChatCompletion(
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
        int count = 0;
        for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
            count += sampledChunks(chapter).size();
        }
        return Math.max(1, count);
    }

    public static int estimateQuestionBankRequests(List<MaterialTextRules.Chapter> chapters) {
        return Math.max(1, MaterialTextRules.chunkWithOverlap(
                chapters,
                QUESTION_BANK_CHUNK_CHARACTERS,
                QUESTION_BANK_CHUNK_OVERLAP
        ).size());
    }

    public static int estimateLearningPlanRequests(List<MaterialTextRules.Chapter> chapters) {
        return Math.max(1, MaterialTextRules.chunk(chapters).size());
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
        int perChapter = Math.max(400, ANALYSIS_SAMPLE_CHARACTERS / chapters.size());
        StringBuilder result = new StringBuilder();
        for (MaterialTextRules.Chapter chapter : chapters) {
            if (chapter == null || chapter.getContent().isEmpty()) {
                continue;
            }
            int remaining = ANALYSIS_SAMPLE_CHARACTERS - result.length();
            if (remaining <= 0) {
                break;
            }
            int allowance = Math.min(perChapter, remaining);
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
            result.append("【").append(chapter.getTitle()).append("】\n").append(sample);
        }
        return result.toString();
    }

    private static List<MaterialTextRules.TextChunk> sampledChunks(
            MaterialGenerationPlan.ChapterPlan chapter
    ) {
        List<MaterialTextRules.TextChunk> chunks = MaterialTextRules.chunk(
                Collections.singletonList(new MaterialTextRules.Chapter(
                        chapter.getTitle(), chapter.getText()
                ))
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

        QuestionBankConversionResult(
                int returnedCount,
                JSONArray accepted,
                int duplicateCount,
                int invalidCount
        ) {
            this.returnedCount = returnedCount;
            this.accepted = accepted;
            this.duplicateCount = duplicateCount;
            this.invalidCount = invalidCount;
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
