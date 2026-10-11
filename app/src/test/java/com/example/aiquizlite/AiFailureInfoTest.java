package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AiFailureInfoTest {
    @Test
    public void preservesNetworkFailureAsNetworkInsteadOfGenericError() {
        AiFailureInfo failure = AiFailureInfo.from(
                new OpenAiCompatibleClient.AiClientException(
                        OpenAiCompatibleClient.FailureKind.NETWORK,
                        "等待 AI 返回超时，请检查网络后重试"
                )
        );

        assertEquals(AiFailureInfo.Kind.NETWORK, failure.getKind());
        assertEquals("网络连接中断", failure.getTitle());
        assertFalse(failure.shouldOfferSettings());
    }

    @Test
    public void distinguishesResponseFailureFromNetworkFailure() {
        AiFailureInfo failure = AiFailureInfo.from(
                new IllegalArgumentException("AI 没有返回可用题目")
        );

        assertEquals(AiFailureInfo.Kind.RESPONSE, failure.getKind());
        assertEquals("AI 返回内容无法使用", failure.getTitle());
    }

    @Test
    public void authenticationFailureOffersSettingsRecovery() {
        AiFailureInfo failure = AiFailureInfo.from(
                new OpenAiCompatibleClient.AiClientException(
                        OpenAiCompatibleClient.FailureKind.AUTH,
                        "API Key无效或没有访问权限"
                )
        );

        assertEquals(AiFailureInfo.Kind.AUTH, failure.getKind());
        assertTrue(failure.shouldOfferSettings());
    }
}
