package com.example.aiquizlite;

import android.content.res.Resources;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.widget.ImageView;

import java.util.Locale;

/** Derive the icon from the saved model ID, so adding a brand also covers old configs. */
final class AiModelIcons {
    enum Brand {
        DEEPSEEK(R.drawable.ai_brand_deepseek, "deepseek"),
        QWEN(R.drawable.ai_brand_qwen, "qwen"),
        OPENAI(R.drawable.ai_brand_openai, "gpt"),
        GEMINI(R.drawable.ai_brand_gemini, "gemini"),
        ZHIPU(R.drawable.ai_brand_zhipu, "glm", "chatglm"),
        KIMI(R.drawable.ai_brand_kimi, "moonshot", "kimi"),
        DOUBAO(R.drawable.ai_brand_doubao, "doubao"),
        STEPFUN(R.drawable.ai_brand_stepfun, "step", "stepfun"),
        DEFAULT(R.drawable.ic_ai_model_default);

        final int drawable;
        final String[] keywords;

        Brand(int drawable, String... keywords) {
            this.drawable = drawable;
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
