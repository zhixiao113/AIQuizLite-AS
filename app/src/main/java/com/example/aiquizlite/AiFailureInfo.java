package com.example.aiquizlite;

import java.util.Locale;

public final class AiFailureInfo {
    public enum Kind {
        NETWORK,
        AUTH,
        RATE_LIMIT,
        SERVICE,
        RESPONSE,
        DOCUMENT,
        UNKNOWN
    }

    private final Kind kind;
    private final String title;
    private final String message;
    private final String recovery;

    private AiFailureInfo(Kind kind, String title, String message, String recovery) {
        this.kind = kind;
        this.title = title;
        this.message = message;
        this.recovery = recovery;
    }

    public static AiFailureInfo from(Throwable error) {
        String message = bestMessage(error);
        if (error instanceof OpenAiCompatibleClient.AiClientException) {
            OpenAiCompatibleClient.AiClientException aiError =
                    (OpenAiCompatibleClient.AiClientException) error;
            if (aiError.isOutputTruncated()) {
                return new AiFailureInfo(Kind.RESPONSE, "AI 返回内容过长", message,
                        "相同内容直接重试仍可能截断。请缩小资料范围，或检查模型输出额度后重试。");
            }
            return fromAiKind(aiError.getKind(), message);
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "api key", "鉴权", "权限", "配置不完整")) {
            return auth(message);
        }
        if (containsAny(normalized, "额度", "余额", "频繁", "429")) {
            return rateLimit(message);
        }
        if (containsAny(normalized, "超时", "网络", "连接", "https", "证书")) {
            return network(message);
        }
        if (containsAny(normalized, "ai服务", "服务暂时不可用", "http 5")) {
            return service(message);
        }
        if (containsAny(normalized, "ai 没有", "返回格式", "空内容", "json", "识别出")) {
            return response(message);
        }
        if (containsAny(normalized, "资料", "文件", "pdf", "docx", "文字")) {
            return document(message);
        }
        return new AiFailureInfo(
                Kind.UNKNOWN,
                "任务未完成",
                message,
                "可以直接重试；若连续失败，请检查 AI 设置或换一个较小的文件。"
        );
    }

    private static AiFailureInfo fromAiKind(
            OpenAiCompatibleClient.FailureKind kind,
            String message
    ) {
        if (kind == null) {
            return from(new IllegalStateException(message));
        }
        switch (kind) {
            case CONFIG:
            case AUTH:
                return auth(message);
            case RATE_LIMIT:
                return rateLimit(message);
            case NETWORK:
                return network(message);
            case SERVICE:
                return service(message);
            case RESPONSE:
                return response(message);
            default:
                return new AiFailureInfo(
                        Kind.UNKNOWN,
                        "AI 请求未完成",
                        message,
                        "可以直接重试；若连续失败，请检查 AI 设置。"
                );
        }
    }

    private static AiFailureInfo network(String message) {
        return new AiFailureInfo(
                Kind.NETWORK,
                "网络连接中断",
                message,
                "请确认网络可用后重试。长资料会自动使用更长的等待时间。"
        );
    }

    private static AiFailureInfo auth(String message) {
        return new AiFailureInfo(
                Kind.AUTH,
                "AI 配置需要处理",
                message,
                "请检查 API 地址、API Key 和模型名称，再返回重试。"
        );
    }

    private static AiFailureInfo rateLimit(String message) {
        return new AiFailureInfo(
                Kind.RATE_LIMIT,
                "请求受限或额度不足",
                message,
                "稍后重试，或检查服务商账户额度与并发限制。"
        );
    }

    private static AiFailureInfo service(String message) {
        return new AiFailureInfo(
                Kind.SERVICE,
                "AI 服务暂时不可用",
                message,
                "这通常是服务商短暂异常，稍后可直接重试。"
        );
    }

    private static AiFailureInfo response(String message) {
        return new AiFailureInfo(
                Kind.RESPONSE,
                "AI 返回内容无法使用",
                message,
                "重试通常会得到新的结果；若仍失败，可换模型或缩小资料。"
        );
    }

    private static AiFailureInfo document(String message) {
        return new AiFailureInfo(
                Kind.DOCUMENT,
                "资料无法读取",
                message,
                "请确认格式受支持、文件未损坏；扫描版 PDF 需要先做 OCR。"
        );
    }

    private static String bestMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                return message.trim();
            }
            current = current.getCause();
        }
        return "发生未知错误";
    }

    private static boolean containsAny(String source, String... values) {
        for (String value : values) {
            if (source.contains(value)) {
                return true;
            }
        }
        return false;
    }

    public Kind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getRecovery() {
        return recovery;
    }

    public boolean shouldOfferSettings() {
        return kind == Kind.AUTH || kind == Kind.RATE_LIMIT;
    }
}
