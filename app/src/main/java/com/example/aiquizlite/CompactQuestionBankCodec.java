package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** Expands AI transport fields only; question recognition is still performed by AI. */
final class CompactQuestionBankCodec {
    private CompactQuestionBankCodec() { }

    static JSONArray decode(String response, List<MaterialTextRules.TextChunk> sources) throws Exception {
        JSONArray rows = MaterialQuestionGenerator.parseModelQuestions(response);
        JSONArray result = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.optBoolean("u", false)) {
                result.put(new JSONObject().put("_unresolved", true)
                        .put("numberInPaper", row.optInt("n", 0))
                        .put("reason", row.optString("reason", "无法可靠转换")));
                continue;
            }
            // Accept the existing schema if a compatible provider ignores the short-field request.
            if (row.has("stem")) {
                result.put(row);
                continue;
            }
            int chapter = row.optInt("c", sources.size() == 1 ? 0 : -1);
            if (chapter < 0 || chapter >= sources.size()) {
                throw new IllegalArgumentException("AI 返回的题目来源编号无效，无法安全还原题库");
            }
            boolean matching = MaterialQuestionRules.MATCHING.equals(
                    MaterialQuestionRules.normalizeType(row.optString("t", "")));
            JSONArray options = new JSONArray();
            if (row.has("o") && !row.isNull("o")) {
                JSONArray pairs = row.getJSONArray("o");
                for (int j = 0; j < pairs.length(); j++) {
                    JSONArray pair = pairs.getJSONArray(j);
                    if (pair.length() != 2) {
                        throw new IllegalArgumentException("AI 返回的选项格式不完整");
                    }
                    options.put(new JSONObject().put("key", pair.getString(0))
                            .put("text", pair.getString(1)));
                }
            }
            JSONObject item = new JSONObject()
                    .put("type", row.getString("t"))
                    .put("stem", row.getString("s"))
                    .put("options", options)
                    .put("answers", matching ? new JSONArray() : row.getJSONArray("a"))
                    .put("explanation", row.has("e") ? row.get("e") : JSONObject.NULL)
                    .put("sourceChapter", sources.get(chapter).getChapterTitle());
            if (row.has("i")) item.put("image", row.get("i"));
            if (row.has("g")) item.put("explanationImages", row.get("g"));
            if (row.optInt("n", 0) > 0) item.put("numberInPaper", row.getInt("n"));
            if (matching) {
                item.put("matching", new JSONObject()
                        .put("mode", row.getString("m"))
                        .put("leftItems", expandItems(row.getJSONArray("l")))
                        .put("rightItems", expandItems(row.getJSONArray("r")))
                        .put("correctPairs", expandPairs(row.getJSONArray("p"))));
            }
            String stem = item.getString("stem");
            if (!stem.isEmpty() && sources.get(chapter).getText().contains(stem)) {
                item.put("sourceSnippet", stem.substring(0, Math.min(80, stem.length())));
            }
            result.put(item);
        }
        return result;
    }

    private static JSONArray expandItems(JSONArray rows) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.getJSONArray(i);
            if (row.length() != 2) throw new IllegalArgumentException("连线项目格式不完整");
            result.put(new JSONObject().put("id", row.getString(0)).put("text", row.getString(1)));
        }
        return result;
    }

    private static JSONArray expandPairs(JSONArray rows) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.getJSONArray(i);
            if (row.length() != 2) throw new IllegalArgumentException("连线关系格式不完整");
            result.put(new JSONObject().put("leftId", row.getString(0))
                    .put("rightId", row.getString(1)));
        }
        return result;
    }
}
