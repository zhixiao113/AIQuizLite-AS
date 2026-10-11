package com.example.aiquizlite;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExtractionRequestTest {
    @Test
    public void officialV4ExtractionExplicitlyDisablesThinking() throws Exception {
        for (String model : new String[]{"deepseek-v4-flash", "deepseek-v4-pro"}) {
            JSONObject body = OpenAiCompatibleClient.requestBody(
                    new ApiConfig("https://api.deepseek.com/v1", "test", model), "system", "source", true);
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
            assertEquals(model, body.getString("model"));
            assertEquals("source", body.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertFalse(body.has("extra_body"));
        }
    }

    @Test
    public void generationAndUnknownProvidersKeepTheirOriginalParameters() throws Exception {
        ApiConfig official = new ApiConfig("https://api.deepseek.com", "test", "deepseek-v4-flash");
        assertFalse(OpenAiCompatibleClient.requestBody(official, "s", "u", false).has("thinking"));
        assertFalse(OpenAiCompatibleClient.requestBody(official, "s", "u", false).has("max_tokens"));
        for (String address : new String[]{"https://relay.example/v1", "https://api.deepseek.com.example/v1"}) {
            assertFalse(OpenAiCompatibleClient.requestBody(
                    new ApiConfig(address, "test", "deepseek-v4-flash"), "s", "u", true).has("max_tokens"));
            assertFalse(OpenAiCompatibleClient.requestBody(
                    new ApiConfig(address, "test", "deepseek-v4-flash"), "s", "u", true).has("thinking"));
        }
        assertFalse(OpenAiCompatibleClient.requestBody(
                new ApiConfig("https://api.deepseek.com", "test", "other-model"), "s", "u", true).has("thinking"));
    }
}
