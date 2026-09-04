package com.example.aiquizlite;

import java.net.URI;

public final class AiEndpoint {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private AiEndpoint() {
    }

    public static String normalizeBaseUrl(String rawBaseUrl) {
        String value = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public static String chatCompletionsUrl(String rawBaseUrl) {
        String baseUrl = normalizeBaseUrl(rawBaseUrl);
        validateHttpsBaseUrl(baseUrl);
        if (baseUrl.endsWith(CHAT_COMPLETIONS_PATH)) {
            return baseUrl;
        }
        return baseUrl + CHAT_COMPLETIONS_PATH;
    }

    public static void validateHttpsBaseUrl(String rawBaseUrl) {
        String baseUrl = normalizeBaseUrl(rawBaseUrl);
        if (baseUrl.isEmpty()) {
            throw new IllegalArgumentException("请填写API地址");
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("API地址必须是完整的HTTPS地址");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("API地址不能包含查询参数或页面片段");
            }
        } catch (IllegalArgumentException e) {
            if ("API地址必须是完整的HTTPS地址".equals(e.getMessage())
                    || "API地址不能包含查询参数或页面片段".equals(e.getMessage())) {
                throw e;
            }
            throw new IllegalArgumentException("API地址格式不正确");
        }
    }
}
