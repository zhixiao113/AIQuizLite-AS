package com.example.aiquizlite;

import android.content.res.Resources;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.widget.ImageView;

import java.util.Locale;

/** Derive the icon from the saved model ID, so adding a brand also covers old configs. */
final class AiModelIcons {
    enum Brand {
        DEEPSEEK(R.drawable.ai_brand_deepseek, "DeepSeek", "deepseek"),
        QWEN(R.drawable.ai_brand_qwen, "通义千问", "qwen"),
        OPENAI(R.drawable.ai_brand_openai, "OpenAI", "gpt"),
        GEMINI(R.drawable.ai_brand_gemini, "Gemini", "gemini"),
        ZHIPU(R.drawable.ai_brand_zhipu, "智谱 AI", "glm", "chatglm"),
        KIMI(R.drawable.ai_brand_kimi, "Kimi", "moonshot", "kimi"),
        DOUBAO(R.drawable.ai_brand_doubao, "豆包", "doubao"),
        STEPFUN(R.drawable.ai_brand_stepfun, "阶跃星辰", "step", "stepfun"),
        DEFAULT(R.drawable.ic_ai_model_default, "AI");

        final int drawable;
        final String label;
        final String[] keywords;

        Brand(int drawable, String label, String... keywords) {
            this.drawable = drawable;
            this.label = label;
            this.keywords = keywords;
        }
    }

    private AiModelIcons() {}

    static Brand match(String modelId) {
        String normalized = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        Brand matched = Brand.DEFAULT;
        int length = 0;
        for (Brand brand : Brand.values()) {
            for (String keyword : brand.keywords) {
                // On equal lengths, retain the earlier vendor in the requirement table.
                if (keyword.length() > length && normalized.contains(keyword)) {
                    matched = brand;
                    length = keyword.length();
                }
            }
        }
        return matched;
    }

    static void bind(ImageView image, String modelId, boolean muted) {
        try {
            image.setImageResource(match(modelId).drawable);
        } catch (Resources.NotFoundException error) {
            image.setImageResource(Brand.DEFAULT.drawable);
        }
        if (muted) {
            ColorMatrix grayscale = new ColorMatrix();
            grayscale.setSaturation(0);
            image.setColorFilter(new ColorMatrixColorFilter(grayscale));
        } else {
            image.clearColorFilter();
        }
        image.setImageAlpha(muted ? 176 : 255);
    }
}
