package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLException;

public final class OpenAiCompatibleClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

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
            throw new AiClientException(e.getMessage());
        }
    }

    String createChatCompletion(
            ApiConfig config,
            String systemPrompt,
            String userPrompt
    ) throws AiClientException {
        if (config == null || !config.isComplete()) {
            throw new AiClientException("AI配置不完整，请检查API地址、API Key和模型名称");
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(AiEndpoint.chatCompletionsUrl(config.getBaseUrl()));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + config.getApiKey());

            JSONObject body = new JSONObject();
            body.put("model", config.getModel());
            JSONArray messages = new JSONArray();
            messages.put(message("system", systemPrompt));
            messages.put(message("user", userPrompt));
            body.put("messages", messages);
            body.put("stream", false);

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
            throw new AiClientException("连接超时，请检查网络或稍后重试");
        } catch (SSLException e) {
            throw new AiClientException("HTTPS连接失败，请检查API地址或证书");
        } catch (Exception e) {
            throw new AiClientException("无法连接AI服务，请检查网络和API地址");
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
                throw new AiClientException("AI服务没有返回可用结果");
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) {
                throw new AiClientException("AI服务返回格式不兼容");
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
            throw new AiClientException("AI服务返回了空内容");
        } catch (AiClientException e) {
            throw e;
        } catch (Exception e) {
            throw new AiClientException("AI服务返回格式不兼容");
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
            return new AiClientException("API Key无效或没有访问权限");
        }
        if (status == 404) {
            return new AiClientException("找不到接口或模型，请检查API地址和模型名称");
        }
        if (status == 429) {
            return new AiClientException("请求过于频繁、额度不足或余额不足");
        }
        if (status >= 500) {
            return new AiClientException("AI服务暂时不可用，请稍后重试");
        }
        return new AiClientException(detail.isEmpty()
                ? "AI服务拒绝了请求（HTTP " + status + "）"
                : detail);
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
        public AiClientException(String message) {
            super(message);
        }
    }
}
