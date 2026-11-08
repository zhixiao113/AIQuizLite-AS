package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenAiCompatibleClientTest {
    @Test
    public void materialRequestsAllowAtLeastThreeMinutesForLongAiResponses() {
        assertTrue(OpenAiCompatibleClient.longReadTimeoutMillis() >= 180_000);
    }

    @Test
    public void explanationsAllowTwoMinutesWithoutChangingMaterialTimeout() {
        assertEquals(120_000, OpenAiCompatibleClient.explanationReadTimeoutMillis());
        assertEquals(180_000, OpenAiCompatibleClient.longReadTimeoutMillis());
    }

    @Test
    public void officialDeepSeekExplanationUsesBoundedNonThinkingRequest() throws Exception {
        for (String model : new String[]{"deepseek-v4-flash", "deepseek-v4-pro", "deepseek-flash"}) {
            ApiConfig config = new ApiConfig("https://api.deepseek.com/v1", "fixture", model);
            org.json.JSONObject body = OpenAiCompatibleClient.explanationRequestBody(config, "system", "prompt");
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
            assertEquals(4096, body.getInt("max_tokens"));
            assertEquals(model, body.getString("model"));
            assertEquals("prompt", body.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertFalse(body.getBoolean("stream"));
        }
    }

    @Test
    public void unknownGatewayAndOtherModelsKeepTheirRequestContract() throws Exception {
        ApiConfig[] configs = {
                new ApiConfig("https://gateway.example.com/v1", "fixture", "deepseek-v4-flash"),
                new ApiConfig("https://api.deepseek.com.evil.example/v1", "fixture", "deepseek-v4-flash"),
                new ApiConfig("https://api.deepseek.com", "fixture", "custom-model")
        };
        for (ApiConfig config : configs) {
            org.json.JSONObject body = OpenAiCompatibleClient.explanationRequestBody(config, "system", "prompt");
            assertFalse(body.has("thinking"));
            assertFalse(body.has("max_tokens"));
        }
        org.json.JSONObject generic = OpenAiCompatibleClient.requestBody(
                new ApiConfig("https://api.deepseek.com", "fixture", "deepseek-v4-flash"), "s", "p", false);
        assertFalse(generic.has("thinking"));
    }

    @Test
    public void retryableFailuresNeverCooldownButProviderFailuresStillDo() {
        for (OpenAiCompatibleClient.FailureKind kind : OpenAiCompatibleClient.FailureKind.values()) {
            OpenAiCompatibleClient.AiClientException error = new OpenAiCompatibleClient.AiClientException(kind, "fixture");
            boolean retryable = kind == OpenAiCompatibleClient.FailureKind.TIMEOUT
                    || kind == OpenAiCompatibleClient.FailureKind.NETWORK
                    || kind == OpenAiCompatibleClient.FailureKind.CANCELLED;
            assertEquals(!retryable, error.shouldCooldown());
            assertEquals(kind == OpenAiCompatibleClient.FailureKind.TIMEOUT, error.isTimeout());
        }
        for (int status : new int[]{408, 504}) {
            OpenAiCompatibleClient.AiClientException error = OpenAiCompatibleClient.httpError(status);
            assertTrue(error.isTimeout());
            assertFalse(error.shouldCooldown());
            assertEquals(status, error.getHttpStatus());
        }
        assertTrue(OpenAiCompatibleClient.httpError(401).shouldCooldown());
        assertTrue(OpenAiCompatibleClient.httpError(429).shouldCooldown());
        assertTrue(OpenAiCompatibleClient.httpError(503).shouldCooldown());
    }

    @Test
    public void parsesStringMessageContent() throws Exception {
        assertEquals(
                "讲解内容",
                OpenAiCompatibleClient.parseContent(
                        "{\"choices\":[{\"message\":{\"content\":\"讲解内容\"}}]}"
                )
        );
    }

    @Test
    public void parsesArrayMessageContentUsedBySomeCompatibleProviders() throws Exception {
        assertEquals(
                "第一段\n第二段",
                OpenAiCompatibleClient.parseContent(
                        "{\"choices\":[{\"message\":{\"content\":["
                                + "{\"type\":\"text\",\"text\":\"第一段\"},"
                                + "{\"type\":\"text\",\"text\":\"第二段\"}]}}]}"
                )
        );
    }

    @Test
    public void cancelledClientStopsBeforeSendingAnyRequest() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient();
        client.cancel();
        OpenAiCompatibleClient.AiClientException failure = org.junit.Assert.assertThrows(
                OpenAiCompatibleClient.AiClientException.class,
                () -> client.generateExplanation(new ApiConfig("https://example.invalid", "fixture", "model"),
                        "system", "prompt"));
        assertTrue(failure.isCancelled());
        assertEquals(OpenAiCompatibleClient.FailureKind.CANCELLED, failure.getKind());
    }
}
