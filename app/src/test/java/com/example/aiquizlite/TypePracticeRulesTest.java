package com.example.aiquizlite;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import static org.junit.Assert.*;

public class TypePracticeRulesTest {
    private Question question(String id, String type) {
        return new Question(id, 1, "卷", 1, type, "题", Collections.emptyList(),
                Collections.singletonList("A"), null, null);
    }
    @Test public void filtersWithoutRenumberingOrChangingOriginalBank() {
        Question a = question("a", "single"), b = question("b", "multi");
        List<Question> bank = Arrays.asList(a, b, question("c", "judge"), question("d", "fill_blank"));
        assertEquals(Collections.singletonList(b), TypePracticeRules.filter(bank, "multi"));
        assertEquals(4, bank.size());
        assertTrue(TypePracticeRules.filter(bank, "matching").isEmpty());
        assertSame(b, TypePracticeRules.filter(bank, "multi").get(0));
    }
    @Test public void sessionKeysSeparateBanksTypesAndDailyPractice() {
        HashSet<String> keys = new HashSet<>();
        for (String bank : Arrays.asList("bank1", "bank2")) {
            for (String type : TypePracticeRules.TYPES) {
                assertTrue(keys.add(TypePracticeRules.sessionKey(bank, type)));
                assertNotEquals("daily_session_" + bank, TypePracticeRules.sessionKey(bank, type));
                assertFalse(TypePracticeRules.label(type).isEmpty());
            }
        }
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnknownType() {
        TypePracticeRules.filter(Collections.emptyList(), "anything");
    }

    @Test public void matchingFilterUsesNormalizedTypeAndKeepsOriginalQuestionNumber() {
        MatchingData data = new MatchingData(MatchingData.ONE_TO_ONE,
                Collections.singletonList(new MatchingData.Item("L1", "左")),
                Collections.singletonList(new MatchingData.Item("R1", "右")),
                Collections.singletonList(new MatchingData.Pair("L1", "R1")));
        Question matching = new Question("q89", 89, "原卷", 89, "连线题", "匹配",
                Collections.emptyList(), Collections.emptyList(), null, null, data, Collections.emptyList());
        List<Question> filtered = TypePracticeRules.filter(Arrays.asList(question("q1", "single"), matching), "matching");
        assertEquals(Collections.singletonList(matching), filtered);
        assertEquals(89, filtered.get(0).getIndex());
        assertEquals(89, filtered.get(0).getNumberInPaper());
    }
}
