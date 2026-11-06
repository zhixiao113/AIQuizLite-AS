package com.example.aiquizlite;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AiCacheRegressionTest {
    private final ApiConfig config = new ApiConfig("https://example.com", "key", "model");
    private Question question(String type, String image, boolean reverse) {
        List<Question.Option> options = new ArrayList<>(Arrays.asList(
                new Question.Option("A", "one"), new Question.Option("B", "two")));
        if (reverse) Collections.reverse(options);
        return new Question("q", 1, "paper", 1, type, "stem", options,
                Arrays.asList("A", "B"), image, "explanation");
    }
    private String key(Question q, List<String> selected) {
        return AiExplanationCache.keyFor("bank", q, selected, true, config);
    }
    @Test public void selectionAndDisplayOrderDoNotCauseRegeneration() {
        assertEquals(key(question("multi", null, false), Arrays.asList("A", "B")),
                key(question("multi", null, true), Arrays.asList("B", "A")));
    }
    @Test public void fillBlankOrderAndImageChangesInvalidate() {
        assertNotEquals(key(question("fill_blank", null, false), Arrays.asList("A", "B")),
                key(question("fill_blank", null, false), Arrays.asList("B", "A")));
        assertNotEquals(key(question("multi", "one.png", false), Arrays.asList("A", "B")),
                key(question("multi", "two.png", false), Arrays.asList("A", "B")));
    }
    @Test public void generatedExplanationSurvivesPersistenceRoundTrip() {
        AiExplanation first = AiExplanation.fromModelText("{\"conclusion\":\"yes\",\"reasoning\":\"because\"}");
        assertEquals(first.toDisplayText(), AiExplanation.fromCachedJson(first.toJson()).toDisplayText());
        assertNull(AiExplanation.fromCachedJson("broken"));
    }
    @Test public void visionRequestContainsRealImagePayload() throws Exception {
        org.json.JSONObject body = OpenAiCompatibleClient.requestBody(config, "system", "question", false);
        OpenAiCompatibleClient.attachImages(body, "question", Collections.singletonList("data:image/png;base64,AAAA"));
        org.json.JSONArray content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertEquals("question", content.getJSONObject(0).getString("text"));
        assertEquals("data:image/png;base64,AAAA", content.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }
    @Test public void questionImageTriggersVision() {
        assertTrue(AiModelExplanation.images(question("multi", null, false)).isEmpty());
        assertEquals(Collections.singletonList("one.png"), AiModelExplanation.images(question("multi", "one.png", false)));
    }

    @Test public void matchingItemAndPairOrderingDoesNotInvalidateCache() {
        List<MatchingData.Item> items = Arrays.asList(new MatchingData.Item("a", "A"), new MatchingData.Item("b", "B"));
        List<MatchingData.Item> reversed = Arrays.asList(items.get(1), items.get(0));
        List<MatchingData.Pair> pairs = Arrays.asList(new MatchingData.Pair("a", "a"), new MatchingData.Pair("b", "b"));
        List<MatchingData.Pair> reversePairs = Arrays.asList(pairs.get(1), pairs.get(0));
        Question first = new Question("q", 1, "", 1, "matching", "match", Collections.emptyList(),
                Collections.emptyList(), null, null,
                new MatchingData(MatchingData.ONE_TO_ONE, items, items, pairs), Collections.emptyList());
        Question second = new Question("q", 1, "", 1, "matching", "match", Collections.emptyList(),
                Collections.emptyList(), null, null,
                new MatchingData(MatchingData.ONE_TO_ONE, reversed, reversed, reversePairs), Collections.emptyList());
        assertEquals(AiExplanationCache.keyFor("bank", first, null, null, pairs, pairs, true, config),
                AiExplanationCache.keyFor("bank", second, null, null, reversePairs, reversePairs, true, config));
    }
}
