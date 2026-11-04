package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class AiProfileImportTest {
    private JSONObject entry(String name) throws Exception {
        return new JSONObject().put("name", name).put("baseUrl", "https://api.example.com/v1/")
                .put("apiKey", "sample-key").put("model", "model");
    }
    private void rejects(String raw) {
        try { AiProfileImport.parse(raw); fail("must reject invalid import"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("sample-key")); }
    }
    @Test public void importsSeveralIndependentProvidersWithRoles() throws Exception {
        String raw = new JSONArray().put(entry("text").put("text", true))
                .put(entry("image").put("vision", true)).put(entry("备用").put("enabled", false)).toString();
        List<AiProfileImport.Entry> values = AiProfileImport.parse(raw);
        assertEquals(3, values.size());
        assertTrue(values.get(0).text); assertFalse(values.get(0).vision);
        assertTrue(values.get(1).vision); assertFalse(values.get(2).enabled);
        assertEquals("https://api.example.com/v1", values.get(0).config.getBaseUrl());
        assertEquals("sample-key", values.get(1).config.getApiKey());
    }
    @Test public void allRowsValidateBeforeAnyPersistence() throws Exception {
        rejects(new JSONArray().put(entry("valid")).put(entry("invalid").put("apiKey", "")).toString());
    }
    @Test public void onlyOneProviderPerRoleIsAccepted() throws Exception {
        rejects(new JSONArray().put(entry("one").put("text", true)).put(entry("two").put("text", true)).toString());
        rejects(new JSONArray().put(entry("one").put("vision", true)).put(entry("two").put("vision", true)).toString());
    }
    @Test public void disabledProviderCannotBeAssigned() throws Exception {
        rejects(new JSONArray().put(entry("disabled").put("enabled", false).put("vision", true)).toString());
    }
    @Test public void oneProviderCanHandleBothRoles() throws Exception {
        AiProfileImport.Entry item = AiProfileImport.parse(new JSONArray()
                .put(entry("both").put("text", true).put("vision", true)).toString()).get(0);
        assertTrue(item.text && item.vision);
    }
    @Test public void urlMustBeHttpsWithoutEmbeddedCredentials() throws Exception {
        rejects(new JSONArray().put(entry("bad").put("baseUrl", "http://example.com")).toString());
        rejects(new JSONArray().put(entry("bad").put("baseUrl", "https://user:sample-key@example.com")).toString());
    }
    @Test public void requiredFieldsAndBooleansAreStrict() throws Exception {
        rejects(new JSONArray().put(entry("bad").put("apiKey", 123)).toString());
        rejects(new JSONArray().put(entry("bad").put("text", "true")).toString());
        rejects(new JSONArray().put(entry("bad").put("name", " ")).toString());
    }
    @Test public void countNamesAndInputSizeAreBounded() throws Exception {
        rejects("[]"); rejects("not JSON"); rejects(null);
        rejects(new JSONArray().put(entry("same")).put(entry("same")).toString());
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 21; i++) tooMany.put(entry("AI" + i));
        rejects(tooMany.toString());
        rejects(String.join("", java.util.Collections.nCopies(AiProfileImport.MAX_CHARACTERS + 1, " ")));
    }
}
