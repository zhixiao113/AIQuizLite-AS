package com.example.aiquizlite;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AiHybridExplanationTest {
    private final ApiConfig text = new ApiConfig("https://text.example", "text-key", "text-model");
    private final ApiConfig vision = new ApiConfig("https://vision.example", "vision-key", "vision-model");
    private final ApiConfig empty = new ApiConfig("", "", "");
    private final List<String> events = new ArrayList<>();
    private class Client extends OpenAiCompatibleClient {
        boolean failVision;
        @Override String describeImages(ApiConfig config, String prompt, List<String> urls) throws AiClientException {
            assertSame(vision, config);
            assertEquals(Collections.singletonList("data:image/png;base64,AAAA"), urls);
            events.add("vision");
            if (failVision) throw new AiClientException("vision failed");
            return "图片中标注了 3 个节点";
        }
        @Override public AiExplanation generateExplanation(ApiConfig config, String system, String prompt) {
            assertSame(text, config);
            events.add("text");
            return new AiExplanation(prompt, "", "", "");
        }
    }
    @Test public void textOnlyDoesNotRequireVision() throws Exception {
        AiHybridExplanation.generate(text, empty, "system", "question", Collections.emptyList(), new Client());
        assertEquals(Collections.singletonList("text"), events);
    }
    @Test public void imagesAreUnderstoodBeforeExplanation() throws Exception {
        AiExplanation result = AiHybridExplanation.generate(text, vision, "system", "question",
                Collections.singletonList("data:image/png;base64,AAAA"), new Client());
        assertEquals(Arrays.asList("vision", "text"), events);
        assertTrue(result.getConclusion().contains("3 个节点"));
    }
    @Test public void visionFailureNeverProducesBlindTextExplanation() throws Exception {
        Client client = new Client(); client.failVision = true;
        try {
            AiHybridExplanation.generate(text, vision, "system", "question",
                    Collections.singletonList("data:image/png;base64,AAAA"), client);
            fail();
        } catch (OpenAiCompatibleClient.AiClientException expected) { assertEquals("vision failed", expected.getMessage()); }
        assertEquals(Collections.singletonList("vision"), events);
    }
    @Test public void unconfiguredVisionStopsBeforeNetwork() throws Exception {
        try {
            AiHybridExplanation.generate(text, empty, "system", "question",
                    Collections.singletonList("data:image/png;base64,AAAA"), new Client());
            fail();
        } catch (OpenAiCompatibleClient.AiClientException expected) {
            assertEquals(OpenAiCompatibleClient.FailureKind.CONFIG, expected.getKind());
        }
        assertTrue(events.isEmpty());
    }
}
