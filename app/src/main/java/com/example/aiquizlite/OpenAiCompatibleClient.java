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
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLException;

public class OpenAiCompatibleClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int EXPLANATION_READ_TIMEOUT_MS = 120_000;
    private static final int LONG_READ_TIMEOUT_MS = 180_000;
    private volatile HttpURLConnection activeConnection;
    private volatile boolean cancelled;

    public enum FailureKind {
        CONFIG,
        NETWORK,
        TIMEOUT,
        AUTH,
        RATE_LIMIT,
        SERVICE,
        RESPONSE,
        CANCELLED,
        UNKNOWN
    }

    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    private void checkCancelled() throws AiClientException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new AiClientException(FailureKind.CANCELLED, "AI 请求已取消");
        }
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
        return generateExplanation(config, systemPrompt, userPrompt, Collections.emptyList());
    }

    AiExplanation generateExplanation(ApiConfig config, String systemPrompt, String userPrompt,
            List<String> images) throws AiClientException {
        String content = createChatCompletion(config, systemPrompt, userPrompt,
                EXPLANATION_READ_TIMEOUT_MS, false, images, CONNECT_TIMEOUT_MS, true);
        try {
            AiExplanation explanation = AiExplanation.fromModelText(content);
            checkCancelled();
            return explanation;
        } catch (IllegalArgumentException e) {
            checkCancelled();
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
                    || "deepseek-v4-pro".equals(config.getModel())
                    || "deepseek-flash".equals(config.getModel()));
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

    static void attachImages(JSONObject body, String userPrompt, List<String> images) throws Exception {
        if (images.isEmpty()) return;
        JSONArray parts = new JSONArray().put(new JSONObject().put("type", "text").put("text", userPrompt));
        for (String image : images) {
            parts.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", image)));
        }
        body.getJSONArray("messages").getJSONObject(1).put("content", parts);
    }

    static JSONObject explanationRequestBody(ApiConfig config, String systemPrompt, String userPrompt)
            throws Exception {
        JSONObject body = requestBody(config, systemPrompt, userPrompt, false);
        if (supportsFastExtraction(config)) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
            body.put("max_tokens", 4096);
        }
        return body;
    }

    static int explanationReadTimeoutMillis() {
        return EXPLANATION_READ_TIMEOUT_MS;
    }

    static int longReadTimeoutMillis() {
        return LONG_READ_TIMEOUT_MS;
    }

    String testVision(ApiConfig config, String image) throws AiClientException {
        return createChatCompletion(config, "请识别图片里的数字。",
                "图片中有四位数字。请只回复你实际读到的四位数字。无法读出时请回复未知。",
                12_000, false, Collections.singletonList(image), 6_000);
    }

    private String createChatCompletion(ApiConfig config, String systemPrompt, String userPrompt,
            int timeout, boolean extraction) throws AiClientException {
        return createChatCompletion(config, systemPrompt, userPrompt, timeout, extraction, Collections.emptyList(), CONNECT_TIMEOUT_MS);
    }

    private String createChatCompletion(
            ApiConfig config,
            String systemPrompt,
            String userPrompt,
            int readTimeoutMillis,
            boolean extraction, List<String> images, int connectTimeoutMillis
    ) throws AiClientException {
        return createChatCompletion(config, systemPrompt, userPrompt, readTimeoutMillis,
                extraction, images, connectTimeoutMillis, false);
    }

    private String createChatCompletion(
            ApiConfig config,
            String systemPrompt,
            String userPrompt,
            int readTimeoutMillis,
            boolean extraction, List<String> images, int connectTimeoutMillis, boolean explanation
    ) throws AiClientException {
        checkCancelled();
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
            activeConnection = connection;
            checkCancelled();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + config.getApiKey());

            JSONObject body = explanation ? explanationRequestBody(config, systemPrompt, userPrompt)
                    : requestBody(config, systemPrompt, userPrompt, extraction);
            attachImages(body, userPrompt, images);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw httpError(status);
            }
            String response = parseContent(readBody(connection.getInputStream()));
            checkCancelled();
            return response;
        } catch (AiClientException e) {
            checkCancelled();
            throw e;
        } catch (IllegalArgumentException e) {
            checkCancelled();
            throw new AiClientException(FailureKind.CONFIG, "API 地址或请求参数无效，请检查配置", e);
        } catch (SocketTimeoutException e) {
            checkCancelled();
            throw new AiClientException(
                    FailureKind.TIMEOUT,
                    "等待 AI 返回超时，请稍后重试，该模型不会因此进入冷却",
                    e
            );
        } catch (SSLException e) {
            checkCancelled();
            throw new AiClientException(
                    FailureKind.NETWORK,
                    "HTTPS连接失败，请检查API地址或证书",
                    e
            );
        } catch (IOException e) {
            checkCancelled();
            throw new AiClientException(
                    FailureKind.NETWORK,
                    "无法连接 AI 服务，请检查网络和 API 地址",
                    e
            );
        } catch (Exception e) {
            checkCancelled();
            throw new AiClientException(
                    FailureKind.UNKNOWN,
                    "AI 请求未完成，请稍后重试",
                    e
            );
        } finally {
            activeConnection = null;
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

    static AiClientException httpError(int status) {
        FailureKind kind = FailureKind.SERVICE;
        String message;
        if (status == 401 || status == 403) {
            kind = FailureKind.AUTH;
            message = "API Key 无效或没有访问权限";
        } else if (status == 404) {
            kind = FailureKind.CONFIG;
            message = "找不到接口或模型，请检查 API 地址和模型 ID";
        } else if (status >= 300 && status < 400) {
            kind = FailureKind.CONFIG;
            message = "API 地址返回重定向，请填写实际接口地址";
        } else if (status == 429 || status == 402) {
            kind = FailureKind.RATE_LIMIT;
            message = "请求过于频繁、额度不足或余额不足";
        } else if (status == 408 || status == 504) {
            kind = FailureKind.TIMEOUT;
            message = "AI 服务响应超时，请稍后重试，该模型不会因此进入冷却";
        } else if (status >= 500) {
            message = "AI 服务暂时不可用，请稍后重试";
        } else {
            message = "AI 服务拒绝了请求（HTTP " + status + "），请检查模型 ID 和接口兼容性";
        }
        return new AiClientException(kind, message, null, false, status);
    }

    public static final class AiClientException extends Exception {
        private final FailureKind kind;
        private final boolean outputTruncated;
        private final int httpStatus;

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
            this(kind, message, cause, outputTruncated, 0);
        }

        private AiClientException(FailureKind kind, String message, Throwable cause, boolean outputTruncated, int httpStatus) {
            super(message, cause);
            this.kind = kind == null ? FailureKind.UNKNOWN : kind;
            this.outputTruncated = outputTruncated;
            this.httpStatus = httpStatus;
        }

        public boolean isOutputTruncated() {
            return outputTruncated;
        }

        int getHttpStatus() { return httpStatus; }

        public FailureKind getKind() {
            return kind;
        }

        public boolean isCancelled() { return kind == FailureKind.CANCELLED; }

        public boolean isTimeout() { return kind == FailureKind.TIMEOUT; }

        public boolean shouldCooldown() {
            return !isOutputTruncated() && !isTimeout()
                    && kind != FailureKind.NETWORK && kind != FailureKind.CANCELLED;
        }
    }
}
