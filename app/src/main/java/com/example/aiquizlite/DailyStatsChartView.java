package com.example.aiquizlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class DailyStatsChartView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<ProgressStore.DailyPracticeRecord> records = new ArrayList<>();
    private final float horizontalPadding;
    private final float topPadding;
    private final float bottomPadding;

    public DailyStatsChartView(Context context) {
        this(context, null);
    }

    public DailyStatsChartView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DailyStatsChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        horizontalPadding = dp(18);
        topPadding = dp(16);
        bottomPadding = dp(28);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(3));
        linePaint.setColor(ContextCompat.getColor(context, R.color.brand_primary));

        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setColor(ContextCompat.getColor(context, R.color.brand_success));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(ContextCompat.getColor(context, R.color.brand_border));

        textPaint.setColor(ContextCompat.getColor(context, R.color.brand_text_secondary));
        textPaint.setTextSize(sp(12));

        emptyPaint.setColor(ContextCompat.getColor(context, R.color.brand_text_muted));
        emptyPaint.setTextSize(sp(14));
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setRecords(List<ProgressStore.DailyPracticeRecord> newRecords) {
        records.clear();
        if (newRecords != null) {
            records.addAll(newRecords);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        if (records.isEmpty()) {
            canvas.drawText("暂无日常刷题记录", width / 2f, height / 2f, emptyPaint);
            return;
        }

        float chartLeft = horizontalPadding;
        float chartRight = width - horizontalPadding;
        float chartTop = topPadding;
        float chartBottom = height - bottomPadding;
        float chartHeight = chartBottom - chartTop;

        drawGrid(canvas, chartLeft, chartRight, chartTop, chartBottom);

        List<ProgressStore.DailyPracticeRecord> visibleRecords = records;
        if (visibleRecords.size() > 10) {
            visibleRecords = visibleRecords.subList(visibleRecords.size() - 10, visibleRecords.size());
        }

        Path path = new Path();
        for (int i = 0; i < visibleRecords.size(); i++) {
            ProgressStore.DailyPracticeRecord record = visibleRecords.get(i);
            float x = visibleRecords.size() == 1
                    ? (chartLeft + chartRight) / 2f
                    : chartLeft + ((chartRight - chartLeft) * i / (visibleRecords.size() - 1f));
            float y = chartBottom - (chartHeight * record.getAccuracyPercent() / 100f);

            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            canvas.drawCircle(x, y, dp(4), pointPaint);
            canvas.drawText(String.valueOf(record.getAccuracyPercent()), x - dp(8), y - dp(8), textPaint);
        }
        canvas.drawPath(path, linePaint);
    }

    private void drawGrid(Canvas canvas, float left, float right, float top, float bottom) {
        float middle = (top + bottom) / 2f;
        canvas.drawLine(left, top, right, top, gridPaint);
        canvas.drawLine(left, middle, right, middle, gridPaint);
        canvas.drawLine(left, bottom, right, bottom, gridPaint);

        canvas.drawText("100%", left, top - dp(4), textPaint);
        canvas.drawText("50%", left, middle - dp(4), textPaint);
        canvas.drawText("0%", left, bottom - dp(4), textPaint);
    }

    private float dp(int value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    private float sp(int value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                getResources().getDisplayMetrics()
        );
    }
}
