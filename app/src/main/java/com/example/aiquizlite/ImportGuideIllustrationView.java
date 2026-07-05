package com.example.aiquizlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class ImportGuideIllustrationView extends View {
    public static final int MODE_LOCAL_FILE = 0;
    public static final int MODE_AI_PASTE = 1;
    public static final int MODE_WECHAT_SHARE = 2;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> labels = new ArrayList<>();
    private int mode = MODE_LOCAL_FILE;

    public ImportGuideIllustrationView(Context context) {
        super(context);
        init();
    }

    public ImportGuideIllustrationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setMode(int mode) {
        this.mode = mode;
        labels.clear();
        if (mode == MODE_AI_PASTE) {
            labels.add("AI");
            labels.add("复制");
            labels.add("导入页");
        } else if (mode == MODE_WECHAT_SHARE) {
            labels.add("电脑");
            labels.add("微信");
            labels.add("AIQuizLite");
        } else {
            labels.add("JSON");
            labels.add("选择文件");
            labels.add("导入页");
        }
        invalidate();
    }

    private void init() {
        setMinimumHeight(dp(104));
        fillPaint.setStyle(Paint.Style.FILL);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.5f));

        textPaint.setColor(0xFF18243A);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(13));
        textPaint.setFakeBoldText(true);

        arrowPaint.setColor(0xFF6D7A8C);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeWidth(dp(2));
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);

        setMode(MODE_LOCAL_FILE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = dp(112);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (labels.isEmpty()) {
            return;
        }

        float width = getWidth();
        float height = getHeight();
        float top = dp(18);
        float boxWidth = Math.min(dp(88), (width - dp(72)) / 3f);
        float boxHeight = dp(58);
        float gap = (width - boxWidth * 3f) / 4f;
        float y = top;

        for (int i = 0; i < 3; i++) {
            float left = gap + i * (boxWidth + gap);
            drawStep(canvas, left, y, boxWidth, boxHeight, labels.get(i), i);
            if (i < 2) {
                drawArrow(canvas, left + boxWidth + dp(6), y + boxHeight / 2f,
                        left + boxWidth + gap - dp(6), y + boxHeight / 2f);
            }
        }

        textPaint.setFakeBoldText(false);
        textPaint.setTextSize(dp(11));
        textPaint.setColor(0xFF6D7A8C);
        String caption = mode == MODE_WECHAT_SHARE
                ? "微信文件通过“用其他应用打开”进入导入页"
                : mode == MODE_AI_PASTE
                ? "复制 AI 生成的 JSON，一键粘贴到导入框"
                : "从手机本地文件选择 JSON，再检查导入";
        canvas.drawText(caption, width / 2f, height - dp(12), textPaint);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(dp(13));
        textPaint.setColor(0xFF18243A);
    }

    private void drawStep(Canvas canvas, float left, float top, float width, float height, String label, int index) {
        int fillColor = index == 2 ? 0xFFEAF4FF : 0xFFF7F9FC;
        int strokeColor = index == 2 ? 0xFF4D8DFF : 0xFFD7DEE8;
        fillPaint.setColor(fillColor);
        strokePaint.setColor(strokeColor);

        RectF rect = new RectF(left, top, left + width, top + height);
        canvas.drawRoundRect(rect, dp(10), dp(10), fillPaint);
        canvas.drawRoundRect(rect, dp(10), dp(10), strokePaint);

        if (mode == MODE_WECHAT_SHARE && index == 1) {
            drawChatBubble(canvas, left + width / 2f, top + dp(21));
        } else if (mode == MODE_AI_PASTE && index == 0) {
            drawSpark(canvas, left + width / 2f, top + dp(20));
        } else {
            drawFile(canvas, left + width / 2f, top + dp(20));
        }
        canvas.drawText(label, left + width / 2f, top + height - dp(12), textPaint);
    }

    private void drawFile(Canvas canvas, float centerX, float centerY) {
        fillPaint.setColor(0xFFFFFFFF);
        strokePaint.setColor(0xFF7C8CA3);
        RectF page = new RectF(centerX - dp(12), centerY - dp(14), centerX + dp(12), centerY + dp(14));
        canvas.drawRoundRect(page, dp(3), dp(3), fillPaint);
        canvas.drawRoundRect(page, dp(3), dp(3), strokePaint);
        arrowPaint.setColor(0xFF7C8CA3);
        canvas.drawLine(centerX - dp(7), centerY - dp(2), centerX + dp(7), centerY - dp(2), arrowPaint);
        canvas.drawLine(centerX - dp(7), centerY + dp(5), centerX + dp(7), centerY + dp(5), arrowPaint);
    }

    private void drawChatBubble(Canvas canvas, float centerX, float centerY) {
        fillPaint.setColor(0xFF2BC66B);
        RectF bubble = new RectF(centerX - dp(15), centerY - dp(11), centerX + dp(15), centerY + dp(9));
        canvas.drawRoundRect(bubble, dp(10), dp(10), fillPaint);
        Path tail = new Path();
        tail.moveTo(centerX + dp(5), centerY + dp(8));
        tail.lineTo(centerX + dp(12), centerY + dp(15));
        tail.lineTo(centerX - dp(1), centerY + dp(9));
        tail.close();
        canvas.drawPath(tail, fillPaint);
    }

    private void drawSpark(Canvas canvas, float centerX, float centerY) {
        fillPaint.setColor(0xFF6F67FF);
        Path path = new Path();
        path.moveTo(centerX, centerY - dp(16));
        path.lineTo(centerX + dp(5), centerY - dp(4));
        path.lineTo(centerX + dp(17), centerY);
        path.lineTo(centerX + dp(5), centerY + dp(5));
        path.lineTo(centerX, centerY + dp(16));
        path.lineTo(centerX - dp(5), centerY + dp(5));
        path.lineTo(centerX - dp(17), centerY);
        path.lineTo(centerX - dp(5), centerY - dp(4));
        path.close();
        canvas.drawPath(path, fillPaint);
    }

    private void drawArrow(Canvas canvas, float startX, float startY, float endX, float endY) {
        arrowPaint.setColor(0xFF6D7A8C);
        canvas.drawLine(startX, startY, endX, endY, arrowPaint);
        Path head = new Path();
        head.moveTo(endX, endY);
        head.lineTo(endX - dp(7), endY - dp(5));
        head.moveTo(endX, endY);
        head.lineTo(endX - dp(7), endY + dp(5));
        canvas.drawPath(head, arrowPaint);
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
