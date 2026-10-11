package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OpenAiCompatibleClientTest {
    @Test
    public void materialRequestsAllowAtLeastThreeMinutesForLongAiResponses() {
        assertTrue(OpenAiCompatibleClient.longReadTimeoutMillis() >= 180_000);
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
}
