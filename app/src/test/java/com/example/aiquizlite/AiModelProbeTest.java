package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.*;

public class AiModelProbeTest {
    private final ApiConfig draft = new ApiConfig("https://example.com/v1/", "fixture-key", "fixture-model");

    private static class Stub extends OpenAiCompatibleClient {
        int visionCalls;
        String answer = "**4736**";
        AiClientException basicError;
        AiClientException visionError;

        @Override public String testConnection(ApiConfig config) throws AiClientException {
            if (basicError != null) throw basicError;
            return "连接成功";
        }

        @Override String testVision(ApiConfig config, String image) throws AiClientException {
            visionCalls++;
            assertTrue(image.startsWith("data:image/png;base64,iVBOR"));
            if (visionError != null) throw visionError;
            return answer;
        }
    }

    @Test public void matchingPictureAnswerBindsProofToUrlModelAndKey() throws Exception {
        Stub stub = new Stub();
        AiModelProbe.Result proof = AiModelProbe.test(draft, stub, 0);
        assertEquals(1, stub.visionCalls);
        assertTrue(proof.visionSupported);
        assertTrue(proof.visionTested);
        assertTrue(proof.matches(new ApiConfig("https://example.com/v1", "fixture-key", "fixture-model")));
        assertFalse(proof.matches(new ApiConfig("https://other.example.com/v1", "fixture-key", "fixture-model")));
        assertFalse(proof.matches(new ApiConfig("https://example.com/v1", "fixture-key", "other-model")));
        assertFalse(proof.matches(new ApiConfig("https://example.com/v1", "other-key", "fixture-model")));
    }

    @Test public void nonVisionAndTransientErrorsDoNotBlockSaving() throws Exception {
        Stub stub = new Stub();
        stub.answer = "不支持图片";
        AiModelProbe.Result text = AiModelProbe.test(draft, stub, 0);
        assertTrue(text.matches(draft));
        assertTrue(text.visionTested);
        assertFalse(text.visionSupported);
        stub.visionError = OpenAiCompatibleClient.httpError(415);
        assertTrue(AiModelProbe.test(draft, stub, 0).visionTested);
        stub.visionError = OpenAiCompatibleClient.httpError(503);
        AiModelProbe.Result unavailable = AiModelProbe.test(draft, stub, 0);
        assertTrue(unavailable.matches(draft));
        assertFalse(unavailable.visionTested);
        assertFalse(unavailable.visionSupported);
    }

    @Test public void basicFailureSkipsVisionAndProvidesNoProof() {
        Stub stub = new Stub();
        stub.basicError = OpenAiCompatibleClient.httpError(401);
        assertSame(stub.basicError, assertThrows(OpenAiCompatibleClient.AiClientException.class,
                () -> AiModelProbe.test(draft, stub, 0)));
        assertEquals(0, stub.visionCalls);
    }
}
