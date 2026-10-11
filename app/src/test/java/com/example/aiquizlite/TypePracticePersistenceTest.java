package com.example.aiquizlite;

import android.content.SharedPreferences;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Exercises the real ProgressStore serializers/keys with an in-memory preference boundary. */
public class TypePracticePersistenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private ProgressStore store() throws Exception {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "putString": values.put((String) args[0], args[1]); return proxy;
                        case "remove": values.remove(args[0]); return proxy;
                        case "clear": values.clear(); return proxy;
                        case "apply": return null;
                        case "commit": return true;
                        default: throw new AssertionError(method.getName());
                    }
                });
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "edit": return editor;
                        case "getString": return values.getOrDefault(args[0], args[1]);
                        case "getAll": return new HashMap<>(values);
                        default: throw new AssertionError(method.getName());
                    }
                });
        return new ProgressStore(preferences, new DailyPracticeHistoryStore(temporary.newFolder()));
    }
    private ProgressStore.DailySession session(String id, boolean matching) {
        return new ProgressStore.DailySession(id, 0, 0, 0, false,
                matching ? Collections.emptyList() : Collections.singletonList("A"),
                Collections.emptyMap(),
                matching ? Collections.emptyMap() : Collections.singletonMap(id, Collections.singletonList("A")),
                matching ? Collections.singletonMap(id, Collections.singletonList(new MatchingData.Pair("L1", "R2")))
                        : Collections.emptyMap());
    }
    @Test public void savingAndClearingTypeDraftsNeverOverwritesDailyOrOtherTypes() throws Exception {
        ProgressStore store = store();
        store.saveDailySession("bank", session("daily", false));
        store.saveTypeSession("bank", "matching", session("match", true));
        store.saveTypeSession("bank", "single", session("single", false));
        assertEquals("daily", store.getDailySession("bank").getQuestionId());
        assertEquals("R2", store.getTypeSession("bank", "matching").getMatchingDrafts()
                .get("match").get(0).getRightId());
        store.clearTypeSession("bank", "matching");
        assertNull(store.getTypeSession("bank", "matching"));
        assertEquals("A", store.getTypeSession("bank", "single").getDraftAnswers().get("single").get(0));
        assertEquals("daily", store.getDailySession("bank").getQuestionId());
        assertTrue(store.getDailyPracticeRecords("bank").isEmpty());
    }
    @Test public void bankDeletionClearsItsTypeDraftsButNotOtherBanks() throws Exception {
        ProgressStore store = store();
        store.saveDailySession("bank", session("daily", false));
        store.saveTypeSession("bank", "matching", session("match", true));
        store.saveTypeSession("other", "matching", session("other", true));
        store.clearQuestionBankData("bank");
        assertNull(store.getDailySession("bank"));
        assertNull(store.getTypeSession("bank", "matching"));
        assertEquals("other", store.getTypeSession("other", "matching").getQuestionId());
    }
    @Test public void invalidatingTypeDraftsAfterBankEditKeepsDailyDraft() throws Exception {
        ProgressStore store = store();
        store.saveDailySession("bank", session("daily", false));
        for (String type : TypePracticeRules.TYPES) store.saveTypeSession("bank", type, session(type, false));
        store.clearTypeSessions("bank");
        for (String type : TypePracticeRules.TYPES) assertNull(store.getTypeSession("bank", type));
        assertEquals("daily", store.getDailySession("bank").getQuestionId());
    }
}
