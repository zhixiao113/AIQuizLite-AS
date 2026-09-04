package com.example.aiquizlite;

public final class ApiConfig {
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public ApiConfig(String baseUrl, String apiKey, String model) {
        this.baseUrl = safeTrim(baseUrl);
        this.apiKey = safeTrim(apiKey);
        this.model = safeTrim(model);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public boolean isComplete() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
