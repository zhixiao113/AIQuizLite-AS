package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QuestionRepository {
    private static final String DEFAULT_ASSET_FILE_NAME = "questions.json";
    private static final String PREF_NAME = "question_repository";
    private static final String KEY_IMPORTED_BANKS = "imported_banks";
    private static final String KEY_BANK_ORDER = "bank_order";
    private static final String KEY_HIDDEN_ASSET_BANKS = "hidden_asset_banks";
    private static final String KEY_ANSWER_OVERRIDES = "answer_overrides";
    private static final String KEY_MATCHING_OVERRIDES = "matching_answer_overrides";
    private static final String IMPORT_DIR_NAME = "question_banks";
    private static final QuestionBank FALLBACK_DEFAULT_QUESTION_BANK =
            QuestionBank.asset("aihcia", "AI-HCIA", DEFAULT_ASSET_FILE_NAME, 0);
    private static final Map<String, String> DISPLAY_NAME_OVERRIDES;
    private static final Map<String, List<Question>> CACHE = new HashMap<>();
    private static List<QuestionBank> questionBanks;

    static {
        Map<String, String> displayNameOverrides = new HashMap<>();
        displayNameOverrides.put("questions.json", "AI-HCIA");
        displayNameOverrides.put("eulercopilot_questions.json", "EulerCopilot");
        displayNameOverrides.put("openeuler_storage_questions.json", "openEuler Storage");
        displayNameOverrides.put("isula_container_questions.json", "iSula Container");
        DISPLAY_NAME_OVERRIDES = Collections.unmodifiableMap(displayNameOverrides);
    }

    private QuestionRepository() {
    }

    public static synchronized List<Question> load(Context context) {
        return load(context, getDefaultQuestionBank(context).getId());
    }

    public static synchronized List<Question> load(Context context, String questionBankId) {
        QuestionBank questionBank = findQuestionBank(context, questionBankId);
        List<Question> cached = CACHE.get(questionBank.getId());
        if (cached != null) {
            return cached;
        }
        try {
            JSONArray jsonArray = questionBank.isImported()
                    ? readQuestionArrayFromFile(questionBank.getStorageName())
                    : readQuestionArrayFromAsset(context, questionBank.getStorageName());
            List<Question> questions = applyMatchingOverrides(applyAnswerOverrides(
                    parseQuestions(jsonArray, questionBank.getDisplayName()),
                    readAnswerOverrides(context, questionBank.getId())
            ), readMatchingOverrides(context, questionBank.getId()));
            List<Question> immutableQuestions = Collections.unmodifiableList(questions);
            CACHE.put(questionBank.getId(), immutableQuestions);
            return immutableQuestions;
        } catch (Exception e) {
            throw new RuntimeException("读取题库失败", e);
        }
    }

    public static synchronized List<QuestionBank> getQuestionBanks(Context context) {
        if (questionBanks != null) {
            return questionBanks;
        }

        List<QuestionBank> discoveredBanks = new ArrayList<>();
        Set<String> hiddenAssets = readStringSet(context, KEY_HIDDEN_ASSET_BANKS);
        try {
            String[] assetNames = context.getAssets().list("");
            if (assetNames != null) {
                for (String assetName : assetNames) {
                    if (!isQuestionBankAsset(assetName)) {
                        continue;
                    }
                    String id = toQuestionBankId(assetName);
                    if (hiddenAssets.contains(id)) {
                        continue;
                    }
                    discoveredBanks.add(QuestionBank.asset(
                            id,
                            resolveDisplayName(context, assetName),
                            assetName,
                            countQuestionsInAsset(context, assetName)
                    ));
                }
            }
        } catch (Exception ignored) {
        }

        discoveredBanks.addAll(readImportedBanks(context));

        if (discoveredBanks.isEmpty()) {
            discoveredBanks.add(FALLBACK_DEFAULT_QUESTION_BANK);
        }

        Map<String, Integer> order = readBankOrder(context);
        discoveredBanks.sort(Comparator
                .comparingInt((QuestionBank bank) -> order.getOrDefault(bank.getId(), Integer.MAX_VALUE))
                .thenComparingInt(QuestionRepository::getSortPriority)
                .thenComparing(QuestionBank::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(QuestionBank::getStorageName, String.CASE_INSENSITIVE_ORDER));

        questionBanks = Collections.unmodifiableList(discoveredBanks);
        return questionBanks;
    }

    public static synchronized QuestionBank getDefaultQuestionBank(Context context) {
        return getQuestionBanks(context).get(0);
    }

    public static QuestionBank getDefaultQuestionBank() {
        if (questionBanks != null && !questionBanks.isEmpty()) {
            return questionBanks.get(0);
        }
        return FALLBACK_DEFAULT_QUESTION_BANK;
    }

    public static synchronized QuestionBank findQuestionBank(Context context, String questionBankId) {
        List<QuestionBank> availableQuestionBanks = getQuestionBanks(context);
        if (questionBankId != null) {
            for (QuestionBank questionBank : availableQuestionBanks) {
                if (questionBank.getId().equals(questionBankId)) {
                    return questionBank;
                }
            }
        }
        return getDefaultQuestionBank(context);
    }

    public static synchronized QuestionBank importQuestionBank(
            Context context,
            String displayName,
            JSONArray questions
    ) throws Exception {
        String trimmedName = displayName == null ? "" : displayName.trim();
        if (trimmedName.isEmpty()) {
            trimmedName = resolveDisplayNameFromQuestions(questions, "自定义题库");
        }

        List<Question> parsedQuestions = parseQuestions(questions, trimmedName);
        String id = "imported_" + System.currentTimeMillis();
        String fileName = id + ".json";
        File bankFile = new File(getImportDir(context), fileName);
        try (FileOutputStream outputStream = new FileOutputStream(bankFile)) {
            outputStream.write(questions.toString(2).getBytes(StandardCharsets.UTF_8));
        }

        QuestionBank questionBank = QuestionBank.imported(
                id,
                trimmedName,
                bankFile.getAbsolutePath(),
                System.currentTimeMillis(),
                parsedQuestions.size()
        );
        List<QuestionBank> importedBanks = new ArrayList<>(readImportedBanks(context));
        importedBanks.add(questionBank);
        saveImportedBanks(context, importedBanks);
        appendBankOrder(context, id);
        resetCache();
        return questionBank;
    }

    public static synchronized boolean deleteQuestionBank(Context context, String questionBankId) {
        List<QuestionBank> banks = getQuestionBanks(context);
        if (banks.size() <= 1) {
            return false;
        }

        QuestionBank target = null;
        for (QuestionBank bank : banks) {
            if (bank.getId().equals(questionBankId)) {
                target = bank;
                break;
            }
        }
        if (target == null) {
            return false;
        }

        if (target.isImported()) {
            List<QuestionBank> importedBanks = new ArrayList<>(readImportedBanks(context));
            for (Iterator<QuestionBank> iterator = importedBanks.iterator(); iterator.hasNext(); ) {
                QuestionBank bank = iterator.next();
                if (bank.getId().equals(questionBankId)) {
                    File file = new File(bank.getStorageName());
                    if (file.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                    File backup = new File(file.getParentFile(), file.getName() + ".bak");
                    File temp = new File(file.getParentFile(), file.getName() + ".tmp");
                    //noinspection ResultOfMethodCallIgnored
                    backup.delete();
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    QuestionImageStore.deleteBankImages(context, questionBankId);
                    iterator.remove();
                }
            }
            saveImportedBanks(context, importedBanks);
        } else {
            Set<String> hiddenAssets = readStringSet(context, KEY_HIDDEN_ASSET_BANKS);
            hiddenAssets.add(questionBankId);
            saveStringSet(context, KEY_HIDDEN_ASSET_BANKS, hiddenAssets);
        }

        removeBankOrder(context, questionBankId);
        preferences(context).edit().remove(namespacedAnswerOverrideKey(questionBankId))
                .remove(KEY_MATCHING_OVERRIDES + "_" + questionBankId).apply();
        CACHE.remove(questionBankId);
        resetCache();
        return true;
    }

    public static synchronized void moveQuestionBank(Context context, String questionBankId, int direction) {
        List<QuestionBank> banks = new ArrayList<>(getQuestionBanks(context));
        int fromIndex = -1;
        for (int i = 0; i < banks.size(); i++) {
            if (banks.get(i).getId().equals(questionBankId)) {
                fromIndex = i;
                break;
            }
        }
        if (fromIndex < 0) {
            return;
        }
        int toIndex = fromIndex + direction;
        if (toIndex < 0 || toIndex >= banks.size()) {
            return;
        }
        Collections.swap(banks, fromIndex, toIndex);
        saveBankOrder(context, banks);
        resetCache();
    }

    public static synchronized void setQuestionBankOrder(Context context, List<String> questionBankIds) {
        JSONArray array = new JSONArray();
        for (String questionBankId : questionBankIds) {
            array.put(questionBankId);
        }
        preferences(context).edit().putString(KEY_BANK_ORDER, array.toString()).apply();
        resetCache();
    }

    public static synchronized void saveAnswerOverride(
            Context context,
            String questionBankId,
            String questionId,
            List<String> answers
    ) {
        Map<String, List<String>> overrides = readAnswerOverrides(context, questionBankId);
        overrides.put(questionId, new ArrayList<>(answers));

        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, List<String>> entry : overrides.entrySet()) {
                JSONArray array = new JSONArray();
                for (String answer : entry.getValue()) {
                    array.put(answer);
                }
                object.put(entry.getKey(), array);
            }
        } catch (Exception e) {
            throw new RuntimeException("保存题库答案修正失败", e);
        }

        preferences(context)
                .edit()
                .putString(namespacedAnswerOverrideKey(questionBankId), object.toString())
                .apply();
        CACHE.remove(questionBankId);
    }

    public static synchronized void removeAnswerOverride(
            Context context,
            String questionBankId,
            String questionId
    ) {
        Map<String, List<String>> overrides = readAnswerOverrides(context, questionBankId);
        if (overrides.remove(questionId) != null) {
            saveAnswerOverrides(context, questionBankId, overrides);
        }
        Map<String, List<MatchingData.Pair>> matchingOverrides = readMatchingOverrides(context,
                questionBankId);
        if (matchingOverrides.remove(questionId) != null) {
            saveMatchingOverrides(context, questionBankId, matchingOverrides);
        }
        CACHE.remove(questionBankId);
    }

    public static synchronized void saveMatchingAnswerOverride(Context context, String bankId,
            Question question, List<MatchingData.Pair> pairs) {
        if (question == null || !question.isMatching()
                || !question.getMatching().isComplete(pairs)) {
            throw new IllegalArgumentException("连线题修正答案必须完整且符合连接规则");
        }
        Map<String, List<MatchingData.Pair>> overrides = readMatchingOverrides(context, bankId);
        overrides.put(question.getId(), new ArrayList<>(pairs));
        saveMatchingOverrides(context, bankId, overrides);
        CACHE.remove(bankId);
    }

    public static synchronized QuestionBank copyQuestionBankForEditing(
            Context context,
            String questionBankId
    ) throws Exception {
        QuestionBank source = findQuestionBank(context, questionBankId);
        List<Question> sourceQuestions = load(context, source.getId());
        JSONArray questions = questionsToJson(sourceQuestions);
        QuestionBank copy = importQuestionBank(
                context,
                source.getDisplayName() + " - 自定义副本",
                questions
        );
        try {
            Map<String, String> copiedImages = new HashMap<>();
            List<Question> copiedQuestions = new ArrayList<>();
            for (Question question : sourceQuestions) {
                String image = question.getImage();
                String copiedImage = null;
                if (image != null && !image.trim().isEmpty()) {
                    copiedImage = copiedImages.get(image);
                    if (copiedImage == null) {
                        copiedImage = QuestionImageStore.copyToBank(
                                context,
                                source.getId(),
                                image,
                                copy.getId()
                        );
                        copiedImages.put(image, copiedImage);
                    }
                }
                List<String> copiedExplanationImages = new ArrayList<>();
                for (String explanationImage : question.getExplanationImages()) {
                    String copiedReference = copiedImages.get(explanationImage);
                    if (copiedReference == null) {
                        copiedReference = QuestionImageStore.copyToBank(
                                context, source.getId(), explanationImage, copy.getId());
                        copiedImages.put(explanationImage, copiedReference);
                    }
                    copiedExplanationImages.add(copiedReference);
                }
                copiedQuestions.add(new Question(
                        question.getId(),
                        question.getIndex(),
                        question.getPaper(),
                        question.getNumberInPaper(),
                        question.getType(),
                        question.getStem(),
                        question.getOptions(),
                        question.getAnswers(),
                        copiedImage,
                        question.getExplanation(),
                        question.getMatching(),
                        copiedExplanationImages, question.getSourceChapter(), question.getSourceName()
                ));
            }
            saveImportedQuestionBank(context, copy.getId(), copiedQuestions);
            return findQuestionBank(context, copy.getId());
        } catch (Exception e) {
            deleteQuestionBank(context, copy.getId());
            throw e;
        }
    }

    public static synchronized void saveImportedQuestionBank(
            Context context,
            String questionBankId,
            List<Question> questions
    ) throws Exception {
        saveImportedQuestionBank(context, questionBankId,
                findQuestionBank(context, questionBankId).getDisplayName(), questions);
    }

    public static synchronized void renameImportedQuestionBank(
            Context context, String questionBankId, String displayName
    ) {
        String name = requireDisplayName(displayName);
        List<QuestionBank> banks = new ArrayList<>(readImportedBanks(context));
        for (int i = 0; i < banks.size(); i++) {
            QuestionBank bank = banks.get(i);
            if (bank.getId().equals(questionBankId)) {
                banks.set(i, QuestionBank.imported(bank.getId(), name, bank.getStorageName(),
                        bank.getImportedAt(), bank.getQuestionCount()));
                saveImportedBanks(context, banks);
                resetCache();
                return;
            }
        }
        throw new IllegalArgumentException("找不到可编辑的题库");
    }

    private static String requireDisplayName(String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("题库名称不能为空");
        }
        return name;
    }

    public static synchronized void saveImportedQuestionBank(
            Context context,
            String questionBankId,
            String displayName,
            List<Question> questions
    ) throws Exception {
        String name = requireDisplayName(displayName);
        QuestionBank bank = findQuestionBank(context, questionBankId);
        if (!bank.isImported() || !bank.getId().equals(questionBankId)) {
            throw new IllegalArgumentException("内置题库不能直接编辑");
        }
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("题库至少需要保留一道题");
        }

        Set<String> ids = new HashSet<>();
        for (Question question : questions) {
            if (question.getId() == null || question.getId().trim().isEmpty()) {
                throw new IllegalArgumentException("题目 ID 不能为空");
            }
            if (!ids.add(question.getId())) {
                throw new IllegalArgumentException("题目 ID 重复：" + question.getId());
            }
        }

        JSONArray jsonArray = questionsToJson(questions);
        parseQuestions(jsonArray, name);
        File target = new File(bank.getStorageName());
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        writeJsonFile(temp, jsonArray);
        if (target.exists()) {
            copyFile(target, backup);
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IllegalStateException("无法替换原题库文件");
        }
        if (!temp.renameTo(target)) {
            if (backup.exists()) {
                copyFile(backup, target);
            }
            throw new IllegalStateException("保存题库失败，已保留备份");
        }

        List<QuestionBank> importedBanks = new ArrayList<>(readImportedBanks(context));
        for (int i = 0; i < importedBanks.size(); i++) {
            QuestionBank item = importedBanks.get(i);
            if (item.getId().equals(questionBankId)) {
                importedBanks.set(i, QuestionBank.imported(
                        item.getId(),
                        name,
                        target.getAbsolutePath(),
                        item.getImportedAt(),
                        questions.size()
                ));
                break;
            }
        }
        saveImportedBanks(context, importedBanks);
        preferences(context).edit().remove(namespacedAnswerOverrideKey(questionBankId))
                .remove(KEY_MATCHING_OVERRIDES + "_" + questionBankId).apply();
        QuestionImageStore.cleanupOrphans(context, questionBankId, questions);
        resetCache();
    }

    public static synchronized void resetCache() {
        CACHE.clear();
        questionBanks = null;
    }

    public static synchronized int clearImportedQuestionBanks(Context context) {
        List<QuestionBank> importedBanks = new ArrayList<>(readImportedBanks(context));
        SharedPreferences.Editor editor = preferences(context).edit();
        Set<String> importedIds = new HashSet<>();
        for (QuestionBank bank : importedBanks) {
            importedIds.add(bank.getId());
            File file = new File(bank.getStorageName());
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(file.getParentFile(), file.getName() + ".bak").delete();
            //noinspection ResultOfMethodCallIgnored
            new File(file.getParentFile(), file.getName() + ".tmp").delete();
            QuestionImageStore.deleteBankImages(context, bank.getId());
            editor.remove(namespacedAnswerOverrideKey(bank.getId()));
            editor.remove(KEY_MATCHING_OVERRIDES + "_" + bank.getId());
        }
        editor.putString(KEY_IMPORTED_BANKS, "[]");
        try {
            JSONArray previousOrder = new JSONArray(
                    preferences(context).getString(KEY_BANK_ORDER, "[]")
            );
            JSONArray retainedOrder = new JSONArray();
            for (int index = 0; index < previousOrder.length(); index++) {
                String id = previousOrder.optString(index, "");
                if (!importedIds.contains(id)) {
                    retainedOrder.put(id);
                }
            }
            editor.putString(KEY_BANK_ORDER, retainedOrder.toString());
        } catch (Exception ignored) {
            editor.remove(KEY_BANK_ORDER);
        }
        if (!editor.commit()) {
            throw new IllegalStateException("无法清除导入题库");
        }
        File importDirectory = getImportDir(context);
        File[] leftovers = importDirectory.listFiles();
        if (leftovers != null) {
            for (File file : leftovers) {
                if (file.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
        resetCache();
        return importedBanks.size();
    }

    private static List<Question> parseQuestions(JSONArray jsonArray, String fallbackPaper) throws Exception {
        List<Question> questions = new ArrayList<>();
        Set<String> questionIds = new HashSet<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject item = jsonArray.getJSONObject(i);
            String questionId = item.has("id")
                    ? (item.isNull("id") ? "" : item.getString("id").trim()) : "q" + (i + 1);
            if (questionId.isEmpty() || !questionIds.add(questionId)) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 题的 ID 为空或重复：" + questionId);
            }
            JSONArray optionsArray = item.optJSONArray("options");
            List<Question.Option> options = new ArrayList<>();
            if (optionsArray != null) {
                for (int j = 0; j < optionsArray.length(); j++) {
                    JSONObject optionItem = optionsArray.getJSONObject(j);
                    options.add(new Question.Option(
                            optionItem.getString("key"),
                            optionItem.getString("text")
                    ));
                }
            }

            String rawType = item.optString("type", "").trim();
            String normalizedType = rawType.isEmpty() ? null
                    : MaterialQuestionRules.normalizeType(rawType);
            if (!rawType.isEmpty() && normalizedType == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 题的题型不受支持：" + rawType);
            }
            boolean matchingType = MaterialQuestionRules.MATCHING.equals(normalizedType);
            JSONArray answersArray = item.optJSONArray("answers");
            List<String> answers = new ArrayList<>();
            if (answersArray != null) {
                for (int j = 0; j < answersArray.length(); j++) {
                    String answer = answersArray.getString(j).trim();
                    if (!answer.isEmpty()) {
                        answers.add(answer);
                    }
                }
            }
            if (!matchingType && answers.isEmpty()) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 题缺少有效答案");
            }

            MatchingData matching = matchingType
                    ? MatchingData.fromJson(item.optJSONObject("matching")) : null;
            List<String> explanationImages = new ArrayList<>();
            JSONArray explanationImageArray = item.optJSONArray("explanationImages");
            if (explanationImageArray != null) {
                for (int j = 0; j < explanationImageArray.length(); j++) {
                    String reference = explanationImageArray.getString(j).trim();
                    if (reference.isEmpty()) throw new IllegalArgumentException("解析图引用不能为空");
                    explanationImages.add(reference);
                }
            }

            Question question = new Question(
                    questionId,
                    item.optInt("index", i + 1),
                    item.optString("paper", fallbackPaper),
                    item.optInt("numberInPaper", i + 1),
                    normalizedType == null ? (answers.size() > 1 ? "multi" : "single") : normalizedType,
                    item.getString("stem"),
                    options,
                    answers,
                    item.isNull("image") ? null : item.optString("image", null),
                    item.isNull("explanation") ? null : item.optString("explanation", null),
                    matching,
                    explanationImages,
                    item.isNull("sourceChapter") ? null : item.optString("sourceChapter", null),
                    item.isNull("sourceName") ? null : item.optString("sourceName", null)
            );
            if (!question.isFillBlank() && !question.isMatching() && options.isEmpty()) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 题缺少选项");
            }
            questions.add(question);
        }
        return questions;
    }

    static List<Question> parsePackagedQuestions(
            JSONArray jsonArray,
            String fallbackPaper
    ) throws Exception {
        return parseQuestions(jsonArray, fallbackPaper);
    }

    private static List<Question> applyAnswerOverrides(
            List<Question> questions,
            Map<String, List<String>> overrides
    ) {
        if (overrides.isEmpty()) {
            return questions;
        }

        List<Question> updatedQuestions = new ArrayList<>();
        for (Question question : questions) {
            List<String> override = overrides.get(question.getId());
            if (override == null || override.isEmpty() || question.isMatching()) {
                updatedQuestions.add(question);
                continue;
            }
            updatedQuestions.add(new Question(
                    question.getId(),
                    question.getIndex(),
                    question.getPaper(),
                    question.getNumberInPaper(),
                    question.getType(),
                    question.getStem(),
                    question.getOptions(),
                    override,
                    question.getImage(),
                    question.getExplanation(),
                    question.getMatching(),
                    question.getExplanationImages(), question.getSourceChapter(), question.getSourceName()
            ));
        }
        return updatedQuestions;
    }

    private static Map<String, List<String>> readAnswerOverrides(Context context, String questionBankId) {
        Map<String, List<String>> overrides = new HashMap<>();
        try {
            String raw = preferences(context).getString(namespacedAnswerOverrideKey(questionBankId), "{}");
            JSONObject object = new JSONObject(raw);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String questionId = keys.next();
                JSONArray array = object.optJSONArray(questionId);
                if (array == null) {
                    continue;
                }
                List<String> answers = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    answers.add(array.optString(i));
                }
                overrides.put(questionId, answers);
            }
        } catch (Exception ignored) {
        }
        return overrides;
    }

    private static List<Question> applyMatchingOverrides(List<Question> questions,
            Map<String, List<MatchingData.Pair>> overrides) {
        if (overrides.isEmpty()) return questions;
        List<Question> result = new ArrayList<>();
        for (Question question : questions) {
            List<MatchingData.Pair> pairs = overrides.get(question.getId());
            if (!question.isMatching() || pairs == null) {
                result.add(question);
                continue;
            }
            try {
                MatchingData old = question.getMatching();
                MatchingData matching = new MatchingData(old.getMode(), old.getLeftItems(),
                        old.getRightItems(), pairs);
                result.add(new Question(question.getId(), question.getIndex(), question.getPaper(),
                        question.getNumberInPaper(), question.getType(), question.getStem(),
                        question.getOptions(), question.getAnswers(), question.getImage(),
                        question.getExplanation(), matching, question.getExplanationImages(),
                        question.getSourceChapter(), question.getSourceName()));
            } catch (IllegalArgumentException invalid) {
                result.add(question);
            }
        }
        return result;
    }

    private static Map<String, List<MatchingData.Pair>> readMatchingOverrides(Context context,
            String bankId) {
        Map<String, List<MatchingData.Pair>> result = new HashMap<>();
        try {
            JSONObject root = new JSONObject(preferences(context).getString(
                    KEY_MATCHING_OVERRIDES + "_" + bankId, "{}"));
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, MatchingData.pairsFromJson(root.optJSONArray(key)));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static void saveMatchingOverrides(Context context, String bankId,
            Map<String, List<MatchingData.Pair>> overrides) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, List<MatchingData.Pair>> entry : overrides.entrySet()) {
                root.put(entry.getKey(), MatchingData.pairsToJson(entry.getValue()));
            }
            preferences(context).edit().putString(KEY_MATCHING_OVERRIDES + "_" + bankId,
                    root.toString()).apply();
        } catch (Exception error) {
            throw new IllegalStateException("保存连线题答案修正失败", error);
        }
    }

    private static void saveAnswerOverrides(
            Context context,
            String questionBankId,
            Map<String, List<String>> overrides
    ) {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, List<String>> entry : overrides.entrySet()) {
                JSONArray array = new JSONArray();
                for (String answer : entry.getValue()) {
                    array.put(answer);
                }
                object.put(entry.getKey(), array);
            }
        } catch (Exception e) {
            throw new RuntimeException("保存题库答案修正失败", e);
        }
        preferences(context).edit()
                .putString(namespacedAnswerOverrideKey(questionBankId), object.toString())
                .apply();
    }

    private static String namespacedAnswerOverrideKey(String questionBankId) {
        return KEY_ANSWER_OVERRIDES + "_" + questionBankId;
    }

    public static JSONArray normalizeImportedJson(String raw) throws Exception {
        String trimmed = sanitizeImportedJsonText(raw);
        if (trimmed.startsWith("[")) {
            return new JSONArray(trimmed);
        }
        JSONObject object = new JSONObject(trimmed);
        JSONArray questions = object.optJSONArray("questions");
        if (questions == null) {
            throw new IllegalArgumentException("JSON 需要是题目数组，或包含 questions 数组");
        }
        return questions;
    }

    public static String resolveDisplayNameFromRawJson(String raw, String fallback) {
        try {
            String trimmed = sanitizeImportedJsonText(raw);
            if (trimmed.startsWith("{")) {
                JSONObject object = new JSONObject(trimmed);
                String name = object.optString("name", "").trim();
                if (!name.isEmpty()) {
                    return name;
                }
                JSONArray questions = object.optJSONArray("questions");
                if (questions != null) {
                    return resolveDisplayNameFromQuestions(questions, fallback);
                }
            }
            if (trimmed.startsWith("[")) {
                return resolveDisplayNameFromQuestions(new JSONArray(trimmed), fallback);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String sanitizeImportedJsonText(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        trimmed = unwrapMarkdownFence(trimmed).trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return extractFirstJsonValue(trimmed, 0);
        }

        int arrayStart = trimmed.indexOf('[');
        int objectStart = trimmed.indexOf('{');
        int start = -1;
        if (arrayStart >= 0 && objectStart >= 0) {
            start = Math.min(arrayStart, objectStart);
        } else if (arrayStart >= 0) {
            start = arrayStart;
        } else if (objectStart >= 0) {
            start = objectStart;
        }
        return start >= 0 ? extractFirstJsonValue(trimmed, start) : trimmed;
    }

    private static String unwrapMarkdownFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        int fenceEnd = text.lastIndexOf("```");
        if (firstLineEnd < 0 || fenceEnd <= firstLineEnd) {
            return text;
        }
        return text.substring(firstLineEnd + 1, fenceEnd);
    }

    private static String extractFirstJsonValue(String text, int start) {
        int end = findJsonValueEnd(text, start);
        if (end < 0) {
            return text.substring(start).trim();
        }
        return text.substring(start, end + 1).trim();
    }

    private static int findJsonValueEnd(String text, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == '{' || current == '[') {
                depth++;
            } else if (current == '}' || current == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static JSONArray readQuestionArrayFromAsset(Context context, String assetFileName) throws Exception {
        try (InputStream inputStream = context.getAssets().open(assetFileName)) {
            return new JSONArray(readAll(inputStream));
        }
    }

    private static JSONArray readQuestionArrayFromFile(String filePath) throws Exception {
        try (InputStream inputStream = new FileInputStream(filePath)) {
            return new JSONArray(readAll(inputStream));
        }
    }

    private static String readAll(InputStream inputStream) throws Exception {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    static JSONArray questionsToJson(List<Question> questions) throws Exception {
        JSONArray array = new JSONArray();
        for (Question question : questions) {
            JSONObject item = new JSONObject();
            item.put("id", question.getId());
            item.put("index", question.getIndex());
            item.put("paper", question.getPaper());
            item.put("numberInPaper", question.getNumberInPaper());
            item.put("type", question.getType());
            item.put("stem", question.getStem());
            item.put("sourceChapter", question.getSourceChapter());
            item.put("sourceName", question.getSourceName());
            JSONArray options = new JSONArray();
            for (Question.Option option : question.getOptions()) {
                JSONObject optionItem = new JSONObject();
                optionItem.put("key", option.getKey());
                optionItem.put("text", option.getText());
                options.put(optionItem);
            }
            item.put("options", options);
            JSONArray answers = new JSONArray();
            for (String answer : question.getAnswers()) {
                answers.put(answer);
            }
            item.put("answers", answers);
            item.put("explanation", question.getExplanation() == null
                    ? JSONObject.NULL
                    : question.getExplanation());
            item.put("image", question.getImage() == null ? JSONObject.NULL : question.getImage());
            if (question.isMatching()) {
                item.put("matching", question.getMatching().toJson());
            }
            item.put("explanationImages", new JSONArray(question.getExplanationImages()));
            array.put(item);
        }
        return array;
    }

    private static void writeJsonFile(File file, JSONArray questions) throws Exception {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(questions.toString(2).getBytes(StandardCharsets.UTF_8));
            outputStream.getFD().sync();
        }
    }

    private static void copyFile(File source, File target) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(target)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
            outputStream.getFD().sync();
        }
    }

    private static boolean isQuestionBankAsset(String assetName) {
        return DEFAULT_ASSET_FILE_NAME.equals(assetName) || assetName.endsWith("_questions.json");
    }

    private static String toQuestionBankId(String assetName) {
        if (DEFAULT_ASSET_FILE_NAME.equals(assetName)) {
            return FALLBACK_DEFAULT_QUESTION_BANK.getId();
        }

        String baseName = assetName.substring(0, assetName.length() - ".json".length());
        if (baseName.endsWith("_questions")) {
            baseName = baseName.substring(0, baseName.length() - "_questions".length());
        }
        return baseName;
    }

    private static String resolveDisplayName(Context context, String assetName) {
        String override = DISPLAY_NAME_OVERRIDES.get(assetName);
        if (override != null) {
            return override;
        }

        try {
            JSONArray jsonArray = readQuestionArrayFromAsset(context, assetName);
            return resolveDisplayNameFromQuestions(jsonArray, prettifyAssetName(assetName));
        } catch (Exception ignored) {
        }

        return prettifyAssetName(assetName);
    }

    private static String resolveDisplayNameFromQuestions(JSONArray jsonArray, String fallback) {
        try {
            if (jsonArray.length() > 0) {
                String paper = jsonArray.getJSONObject(0).optString("paper", "").trim();
                if (!paper.isEmpty()) {
                    return normalizePaperName(paper);
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static int countQuestionsInAsset(Context context, String assetName) {
        try {
            return readQuestionArrayFromAsset(context, assetName).length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String normalizePaperName(String paper) {
        if (paper.endsWith("题库") && paper.length() > 2) {
            return paper.substring(0, paper.length() - 2);
        }
        return paper;
    }

    private static String prettifyAssetName(String assetName) {
        String baseName = assetName.substring(0, assetName.length() - ".json".length());
        if (baseName.endsWith("_questions")) {
            baseName = baseName.substring(0, baseName.length() - "_questions".length());
        }

        String[] parts = baseName.replace('-', '_').split("_+");
        StringBuilder displayName = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (displayName.length() > 0) {
                displayName.append(' ');
            }
            displayName.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                displayName.append(part.substring(1));
            }
        }
        return displayName.length() == 0 ? baseName : displayName.toString();
    }

    private static List<QuestionBank> readImportedBanks(Context context) {
        try {
            String raw = preferences(context).getString(KEY_IMPORTED_BANKS, "[]");
            JSONArray array = new JSONArray(raw);
            List<QuestionBank> banks = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                File file = new File(object.optString("path", ""));
                if (!file.exists()) {
                    continue;
                }
                banks.add(QuestionBank.imported(
                        object.getString("id"),
                        object.getString("displayName"),
                        file.getAbsolutePath(),
                        object.optLong("importedAt", 0L),
                        object.optInt("questionCount", 0)
                ));
            }
            return banks;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static void saveImportedBanks(Context context, List<QuestionBank> banks) {
        JSONArray array = new JSONArray();
        try {
            for (QuestionBank bank : banks) {
                JSONObject object = new JSONObject();
                object.put("id", bank.getId());
                object.put("displayName", bank.getDisplayName());
                object.put("path", bank.getStorageName());
                object.put("importedAt", bank.getImportedAt());
                object.put("questionCount", bank.getQuestionCount());
                array.put(object);
            }
        } catch (Exception e) {
            throw new RuntimeException("保存导入题库失败", e);
        }
        preferences(context).edit().putString(KEY_IMPORTED_BANKS, array.toString()).apply();
    }

    private static Map<String, Integer> readBankOrder(Context context) {
        Map<String, Integer> order = new HashMap<>();
        try {
            JSONArray array = new JSONArray(preferences(context).getString(KEY_BANK_ORDER, "[]"));
            for (int i = 0; i < array.length(); i++) {
                order.put(array.getString(i), i);
            }
        } catch (Exception ignored) {
        }
        return order;
    }

    private static void saveBankOrder(Context context, List<QuestionBank> banks) {
        JSONArray array = new JSONArray();
        for (QuestionBank bank : banks) {
            array.put(bank.getId());
        }
        preferences(context).edit().putString(KEY_BANK_ORDER, array.toString()).apply();
    }

    private static void appendBankOrder(Context context, String questionBankId) {
        List<QuestionBank> banks = new ArrayList<>(getQuestionBanks(context));
        boolean exists = false;
        for (QuestionBank bank : banks) {
            if (bank.getId().equals(questionBankId)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            banks.add(new QuestionBank(questionBankId, questionBankId, "", false, 0L, 0));
        }
        saveBankOrder(context, banks);
    }

    private static void removeBankOrder(Context context, String questionBankId) {
        List<QuestionBank> banks = new ArrayList<>(getQuestionBanks(context));
        for (Iterator<QuestionBank> iterator = banks.iterator(); iterator.hasNext(); ) {
            if (iterator.next().getId().equals(questionBankId)) {
                iterator.remove();
            }
        }
        saveBankOrder(context, banks);
    }

    private static Set<String> readStringSet(Context context, String key) {
        Set<String> values = new HashSet<>();
        try {
            JSONArray array = new JSONArray(preferences(context).getString(key, "[]"));
            for (int i = 0; i < array.length(); i++) {
                values.add(array.getString(i));
            }
        } catch (Exception ignored) {
        }
        return values;
    }

    private static void saveStringSet(Context context, String key, Set<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        preferences(context).edit().putString(key, array.toString()).apply();
    }

    private static File getImportDir(Context context) {
        File dir = new File(context.getFilesDir(), IMPORT_DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static int getSortPriority(QuestionBank questionBank) {
        if (DEFAULT_ASSET_FILE_NAME.equals(questionBank.getStorageName())) {
            return 0;
        }
        return questionBank.isImported() ? 2 : 1;
    }

    public static final class QuestionBank {
        private final String id;
        private final String displayName;
        private final String storageName;
        private final boolean imported;
        private final long importedAt;
        private final int questionCount;

        private QuestionBank(
                String id,
                String displayName,
                String storageName,
                boolean imported,
                long importedAt,
                int questionCount
        ) {
            this.id = id;
            this.displayName = displayName;
            this.storageName = storageName;
            this.imported = imported;
            this.importedAt = importedAt;
            this.questionCount = questionCount;
        }

        static QuestionBank asset(String id, String displayName, String assetFileName, int questionCount) {
            return new QuestionBank(id, displayName, assetFileName, false, 0L, questionCount);
        }

        static QuestionBank imported(String id, String displayName, String filePath, long importedAt, int questionCount) {
            return new QuestionBank(id, displayName, filePath, true, importedAt, questionCount);
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getStorageName() {
            return storageName;
        }

        public String getAssetFileName() {
            return storageName;
        }

        public boolean isImported() {
            return imported;
        }

        public long getImportedAt() {
            return importedAt;
        }

        public int getQuestionCount() {
            return questionCount;
        }
    }
}
