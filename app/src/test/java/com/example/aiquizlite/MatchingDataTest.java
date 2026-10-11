package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MatchingDataTest {
    private MatchingData bgp() {
        return new MatchingData(MatchingData.LEFT_REUSABLE,
                Arrays.asList(new MatchingData.Item("L1", "公认必须遵循"),
                        new MatchingData.Item("L2", "公认任意"),
                        new MatchingData.Item("L3", "可选过渡"),
                        new MatchingData.Item("L4", "可选非过渡")),
                Arrays.asList(new MatchingData.Item("R1", "Origin"),
                        new MatchingData.Item("R2", "团体"),
                        new MatchingData.Item("R3", "Next_Hop"),
                        new MatchingData.Item("R4", "Local_Pref"),
                        new MatchingData.Item("R5", "Originator_ID"),
                        new MatchingData.Item("R6", "Cluster_List")),
                Arrays.asList(new MatchingData.Pair("L1", "R1"),
                        new MatchingData.Pair("L3", "R2"),
                        new MatchingData.Pair("L1", "R3"),
                        new MatchingData.Pair("L2", "R4"),
                        new MatchingData.Pair("L4", "R5"),
                        new MatchingData.Pair("L4", "R6")));
    }

    @Test public void matchingComparesStablePairsRegardlessOfDrawingOrder() throws Exception {
        MatchingData data = MatchingData.fromJson(bgp().toJson());
        assertTrue(data.isCorrect(Arrays.asList(new MatchingData.Pair("L4", "R6"),
                new MatchingData.Pair("L1", "R3"), new MatchingData.Pair("L3", "R2"),
                new MatchingData.Pair("L4", "R5"), new MatchingData.Pair("L2", "R4"),
                new MatchingData.Pair("L1", "R1"))));
        assertEquals(5, data.countCorrect(Arrays.asList(new MatchingData.Pair("L4", "R6"),
                new MatchingData.Pair("L1", "R3"), new MatchingData.Pair("L2", "R2"),
                new MatchingData.Pair("L4", "R5"), new MatchingData.Pair("L2", "R4"),
                new MatchingData.Pair("L1", "R1"))));
        assertFalse(data.isComplete(Collections.singletonList(new MatchingData.Pair("L1", "R1"))));
    }

    @Test public void oneToOneAllowsUnusedLeftButRejectsReusedLeft() {
        MatchingData data = new MatchingData(MatchingData.ONE_TO_ONE,
                Arrays.asList(new MatchingData.Item("L1", "未用"),
                        new MatchingData.Item("L2", "A"), new MatchingData.Item("L3", "B")),
                Arrays.asList(new MatchingData.Item("R1", "1"), new MatchingData.Item("R2", "2")),
                Arrays.asList(new MatchingData.Pair("L2", "R1"), new MatchingData.Pair("L3", "R2")));
        assertTrue(data.isComplete(data.getCorrectPairs()));
        assertFalse(data.isValidSelection(Arrays.asList(new MatchingData.Pair("L2", "R1"),
                new MatchingData.Pair("L2", "R2"))));
    }

    @Test public void normalizerRetainsMatchingAndPaperNumber() throws Exception {
        JSONObject candidate = new JSONObject().put("type", "matching").put("stem", "匹配属性")
                .put("numberInPaper", 89).put("matching", bgp().toJson());
        JSONArray accepted = MaterialQuestionRules.validateDetected(
                new JSONArray().put(candidate), "HCIP").getAccepted();
        assertEquals(1, accepted.length());
        assertEquals(89, accepted.getJSONObject(0).getInt("numberInPaper"));
        assertEquals(6, accepted.getJSONObject(0).getJSONObject("matching")
                .getJSONArray("correctPairs").length());
        assertEquals(0, accepted.getJSONObject(0).getJSONArray("answers").length());
    }
}
