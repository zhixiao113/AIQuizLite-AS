package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AiEndpointTest {
    @Test
    public void appendsChatCompletionsToBaseUrl() {
        assertEquals(
                "https://api.example.com/v1/chat/completions",
                AiEndpoint.chatCompletionsUrl(" https://api.example.com/v1/// ")
        );
    }

    @Test
    public void preservesFullChatCompletionsUrl() {
        assertEquals(
                "https://api.example.com/v1/chat/completions",
                AiEndpoint.chatCompletionsUrl("https://api.example.com/v1/chat/completions")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCleartextHttpBecauseApiKeyWouldBeExposed() {
        AiEndpoint.chatCompletionsUrl("http://api.example.com/v1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsQueryParametersThatWouldCorruptTheEndpoint() {
        AiEndpoint.chatCompletionsUrl("https://api.example.com/v1?token=unexpected");
    }
}
