package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLException;

public class OpenAiCompatibleClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int LONG_READ_TIMEOUT_MS = 180_000;

    public enum FailureKind {
        CONFIG,
        NETWORK,
        AUTH,
        RATE_LIMIT,
        SERVICE,
        RESPONSE,
        UNKNOWN
    }

    public String testConnection(ApiConfig config) throws AiClientException {
        return createChatCompletion(
                config,
                "你正在执行连接测试。",
                "只回复：连接成功"
        );
    }

    public AiExplanation generateExplanation(
            ApiConfig config,
            String systemPrompt,
            String userPrompt
    ) throws AiClientException {
        String content = createChatCompletion(config, systemPrompt, userPrompt);
        try {
            return AiExplanation.fromModelText(content);
        } catch (IllegalArgumentException e) {
            throw new AiClientException(FailureKind.RESPONSE, e.getMessage(), e);
        }
    }

    String createChatCompletion(
            ApiConfig config,
            String systemPrompt,
            String userPrompt
    ) throws AiClientException {
        return createChatCompletion(config, systemPrompt, userPrompt, READ_TIMEOUT_MS, false);
    }

    String createLongChatCompletion(
            ApiConfig config,
            String systemPrompt,
            String userPrompt
    ) throws AiClientException {
        return createChatCompletion(config, systemPrompt, userPrompt, LONG_READ_TIMEOUT_MS, false);
    }

    String createExtractionChatCompletion(ApiConfig config, String systemPrompt, String userPrompt)
            throws AiClientException {
        // Unknown compatible gateways retain their existing request contract.
        if (!supportsFastExtraction(config)) {
            return createLongChatCompletion(config, systemPrompt, userPrompt);
        }
        return createChatCompletion(config, systemPrompt, userPrompt, LONG_READ_TIMEOUT_MS, true);
    }

    private static boolean supportsFastExtraction(ApiConfig config) {
        if (config == null) {
            return false;
        }
        try {
            return "api.deepseek.com".equalsIgnoreCase(new URL(config.getBaseUrl()).getHost())
                    && ("deepseek-v4-flash".equals(config.getModel())
                    || "deepseek-v4-pro".equals(config.getModel()));
        } catch (Exception ignored) {
            return false;
        }
    }

    static JSONObject requestBody(ApiConfig config, String systemPrompt, String userPrompt,
            boolean extraction) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", config.getModel());
        JSONArray messages = new JSONArray();
        messages.put(message("system", systemPrompt));
        messages.put(message("user", userPrompt));
        body.put("messages", messages);
        body.put("stream", false);
        if (extraction && supportsFastExtraction(config)) {
            // Official DeepSeek V4 defaults to thinking/high; transcription needs no added reasoning.
            body.put("thinking", new JSONObject().put("type", "disabled"));
            // A ceiling, not a target: short classification responses still finish immediately.
            // Leave room for JSON overhead instead of relying on the provider's default allowance.
            body.put("max_tokens", (int) Math.min(65_536L,
                    Math.max(8_192L, (long) userPrompt.length() * 3)));
        }
        return body;
    }

    static int longReadTimeoutMillis() {
        return LONG_READ_TIMEOUT_MS;
    }

    private String createChatCompletion(
            ApiConfig config,
            String systemPrompt,
            String userPrompt,
            int readTimeoutMillis,
            boolean extraction
    ) throws AiClientException {
        if (config == null || !config.isComplete()) {
            throw new AiClientException(
                    FailureKind.CONFIG,
                    "AI配置不完整，请检查API地址、API Key和模型名称"
            );
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(AiEndpoint.chatCompletionsUrl(config.getBaseUrl()));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + config.getApiKey());

            JSONObject body = requestBody(config, systemPrompt, userPrompt, extraction);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            String responseBody = readBody(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw httpError(status, responseBody);
            }
            return parseContent(responseBody);
        } catch (AiClientException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new AiClientException(
                    FailureKind.NETWORK,
                    "等待 AI 返回超时，请检查网络后重试",
                    e
            );
        } catch (SSLException e) {
            throw new AiClientException(
                    FailureKind.NETWORK,
                    "HTTPS连接失败，请检查API地址或证书",
                    e
            );
        } catch (IOException e) {
            throw new AiClientException(
                    FailureKind.NETWORK,
                    "无法连接 AI 服务，请检查网络和 API 地址",
                    e
            );
        } catch (Exception e) {
            throw new AiClientException(
                    FailureKind.UNKNOWN,
                    "AI 请求未完成，请稍后重试",
                    e
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String parseContent(String responseBody) throws AiClientException {
        try {
            JSONObject response = new JSONObject(responseBody);
            JSONArray choices = response.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new AiClientException(FailureKind.RESPONSE, "AI服务没有返回可用结果");
            }
            if ("length".equals(choices.getJSONObject(0).optString("finish_reason"))) {
                throw new AiClientException(FailureKind.RESPONSE,
                        "本次 AI 输出额度或上下文长度达到上限，结果被截断。请缩小资料范围后重试",
                        null, true);
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) {
                throw new AiClientException(FailureKind.RESPONSE, "AI服务返回格式不兼容");
            }
            Object content = message.opt("content");
            if (content instanceof String) {
                String text = ((String) content).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
            if (content instanceof JSONArray) {
                JSONArray parts = (JSONArray) content;
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part == null) {
                        continue;
                    }
                    String text = part.optString("text", "").trim();
                    if (!text.isEmpty()) {
                        if (builder.length() > 0) {
                            builder.append('\n');
                        }
                        builder.append(text);
                    }
                }
                if (builder.length() > 0) {
                    return builder.toString();
                }
            }
            throw new AiClientException(FailureKind.RESPONSE, "AI服务返回了空内容");
        } catch (AiClientException e) {
            throw e;
        } catch (Exception e) {
            throw new AiClientException(FailureKind.RESPONSE, "AI服务返回格式不兼容", e);
        }
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static String readBody(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                inputStream,
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static AiClientException httpError(int status, String body) {
        String detail = extractErrorMessage(body);
        if (status == 401 || status == 403) {
            return new AiClientException(FailureKind.AUTH, "API Key无效或没有访问权限");
        }
        if (status == 404) {
            return new AiClientException(
                    FailureKind.CONFIG,
                    "找不到接口或模型，请检查API地址和模型名称"
            );
        }
        if (status == 429) {
            return new AiClientException(
                    FailureKind.RATE_LIMIT,
                    "请求过于频繁、额度不足或余额不足"
            );
        }
        if (status >= 500) {
            return new AiClientException(FailureKind.SERVICE, "AI服务暂时不可用，请稍后重试");
        }
        return new AiClientException(
                FailureKind.SERVICE,
                detail.isEmpty() ? "AI服务拒绝了请求（HTTP " + status + "）" : detail
        );
    }

    private static String extractErrorMessage(String body) {
        try {
            JSONObject object = new JSONObject(body);
            JSONObject error = object.optJSONObject("error");
            if (error != null) {
                return error.optString("message", "").trim();
            }
            String message = object.optString("message", "").trim();
            if (!message.isEmpty()) {
                return message;
            }
            return object.optString("detail", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static final class AiClientException extends Exception {
        private final FailureKind kind;
        private final boolean outputTruncated;

        public AiClientException(String message) {
            this(FailureKind.UNKNOWN, message, null);
        }

        public AiClientException(FailureKind kind, String message) {
            this(kind, message, null);
        }

        public AiClientException(FailureKind kind, String message, Throwable cause) {
            this(kind, message, cause, false);
        }

        private AiClientException(FailureKind kind, String message, Throwable cause, boolean outputTruncated) {
            super(message, cause);
            this.kind = kind == null ? FailureKind.UNKNOWN : kind;
            this.outputTruncated = outputTruncated;
        }

        public boolean isOutputTruncated() {
            return outputTruncated;
        }

        public FailureKind getKind() {
            return kind;
        }
    }
}
