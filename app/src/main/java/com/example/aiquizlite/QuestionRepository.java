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
            List<Question> questions = applyAnswerOverrides(
                    parseQuestions(jsonArray, questionBank.getDisplayName()),
                    readAnswerOverrides(context, questionBank.getId())
            );
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

    public static synchronized void resetCache() {
        CACHE.clear();
        questionBanks = null;
    }

    private static List<Question> parseQuestions(JSONArray jsonArray, String fallbackPaper) throws Exception {
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject item = jsonArray.getJSONObject(i);
            JSONArray optionsArray = item.getJSONArray("options");
            List<Question.Option> options = new ArrayList<>();
            for (int j = 0; j < optionsArray.length(); j++) {
                JSONObject optionItem = optionsArray.getJSONObject(j);
                options.add(new Question.Option(
                        optionItem.getString("key"),
                        optionItem.getString("text")
                ));
            }

            JSONArray answersArray = item.getJSONArray("answers");
            List<String> answers = new ArrayList<>();
            for (int j = 0; j < answersArray.length(); j++) {
                answers.add(answersArray.getString(j));
            }

            questions.add(new Question(
                    item.optString("id", "q" + (i + 1)),
                    item.optInt("index", i + 1),
                    item.optString("paper", fallbackPaper),
                    item.optInt("numberInPaper", i + 1),
                    item.optString("type", answers.size() > 1 ? "multi" : "single"),
                    item.getString("stem"),
                    options,
                    answers,
                    item.isNull("image") ? null : item.optString("image", null)
            ));
        }
        return questions;
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
            if (override == null || override.isEmpty()) {
                updatedQuestions.add(question);
                continue;
            }
            updatedQuestions.add(new Question(
                    question.getId(),
                    question.getIndex(),
                    question.getPaper(),
                    question.getNumberInPaper(),
                    override.size() > 1 ? "multi" : question.getType(),
                    question.getStem(),
                    question.getOptions(),
                    override,
                    question.getImage()
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

    private static String namespacedAnswerOverrideKey(String questionBankId) {
        return KEY_ANSWER_OVERRIDES + "_" + questionBankId;
    }

    public static JSONArray normalizeImportedJson(String raw) throws Exception {
        String trimmed = raw == null ? "" : raw.trim();
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
            String trimmed = raw == null ? "" : raw.trim();
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
