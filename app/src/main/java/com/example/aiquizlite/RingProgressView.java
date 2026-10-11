package com.example.aiquizlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/**
 * 圆环进度控件（更新下载页使用）：
 * 外圈浅灰蓝底环 + 蓝色渐变进度弧（圆头、从正上方顺时针）+ 中央百分比与"已完成"文字。
 */
public class RingProgressView extends View {
    private static final int STROKE_WIDTH_DP = 10;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float progress = 0f; // 0.0 ~ 1.0

    public RingProgressView(Context context) {
        this(context, null);
    }

    public RingProgressView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RingProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;

        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(STROKE_WIDTH_DP * density);
        bgPaint.setColor(0xFFE9F0FA);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(STROKE_WIDTH_DP * density);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        pctPaint.setAntiAlias(true);
        pctPaint.setTextAlign(Paint.Align.CENTER);
        pctPaint.setColor(0xFF2563EB);
        pctPaint.setFakeBoldText(true);
        pctPaint.setTextSize(30f * density);

        labelPaint.setAntiAlias(true);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(0xFF64748B);
        labelPaint.setTextSize(10f * density);
    }

    public void setProgress(int percent) {
        this.progress = Math.max(0f, Math.min(100f, percent)) / 100f;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int size = Math.min(width, height);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float stroke = bgPaint.getStrokeWidth();
        float radius = Math.min(cx, cy) - stroke / 2f - 2f * density;
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 底环
        canvas.drawArc(arcRect, 0f, 360f, false, bgPaint);

        // 进度弧：渐变 + 从正上方顺时针
        if (progress > 0f) {
            SweepGradient gradient = new SweepGradient(
                    cx, cy,
                    new int[]{0xFF3B82F6, 0xFF2563EB},
                    null
            );
            arcPaint.setShader(gradient);
            canvas.save();
            canvas.rotate(-90f, cx, cy);
            canvas.drawArc(arcRect, 0f, 360f * progress, false, arcPaint);
            canvas.restore();
            arcPaint.setShader(null);
        }

        // 中央百分比
        String pct = String.valueOf((int) (progress * 100f)) + "%";
        float baseline = cy + 2f * density;
        canvas.drawText(pct, cx, baseline, pctPaint);

        // 中央小字
        canvas.drawText("已完成", cx, cy + 30f * density, labelPaint);
    }
}
