package com.example.aiquizlite;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AiModelExplanation {
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;

    private AiModelExplanation() {}

    static List<String> images(Question question) {
        List<String> refs = new ArrayList<>();
        if (question.getImage() != null && !question.getImage().trim().isEmpty()) refs.add(question.getImage());
        for (String ref : question.getExplanationImages()) if (!refs.contains(ref)) refs.add(ref);
        return refs;
    }
    static AiExplanationRouter.Result generate(Context context, String bank, Question question,
            String system, String prompt) throws OpenAiCompatibleClient.AiClientException {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient();
        List<String> refs = images(question);
        return AiExplanationRouter.generate(new AiProfiles(context), !refs.isEmpty(),
                System::currentTimeMillis, () -> {
                    List<String> images = refs.isEmpty() ? Collections.emptyList() : readImages(context, bank, refs);
                    String fullPrompt = refs.isEmpty() ? prompt : prompt +
                            "\n请结合本次附带的题目和解析图片讲解。对看不清的内容明确说明，不要猜测。";
                    return config -> client.generateExplanation(config, system, fullPrompt, images);
                });
    }

    private static List<String> readImages(Context context, String bank, List<String> refs)
            throws OpenAiCompatibleClient.AiClientException {
        List<String> urls = new ArrayList<>();
        byte[] buffer = new byte[8192];
        int total = 0;
        try {
            for (String ref : refs) {
                try (InputStream in = QuestionImageStore.open(context, bank, ref);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片总大小超过 12 MB");
                        out.write(buffer, 0, n);
                    }
                    byte[] bytes = out.toByteArray();
                    String mime = imageMime(bytes);
                    if (mime == null) throw new IllegalArgumentException("图片格式暂不支持，请使用 PNG、JPEG、GIF 或 WebP");
                    urls.add("data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP));
                }
            }
        } catch (Exception error) {
            String message = error instanceof IllegalArgumentException ? error.getMessage() : "题库图片缺失或无法打开";
            throw new OpenAiCompatibleClient.AiClientException(OpenAiCompatibleClient.FailureKind.CONFIG,
                    "题图读取失败：" + message, error);
        }
        return urls;
    }

    static String imageMime(byte[] bytes) throws java.io.IOException {
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        String mime = java.net.URLConnection.guessContentTypeFromStream(new java.io.ByteArrayInputStream(bytes));
        return "image/png".equals(mime) || "image/gif".equals(mime) ? mime : null;
    }
}
