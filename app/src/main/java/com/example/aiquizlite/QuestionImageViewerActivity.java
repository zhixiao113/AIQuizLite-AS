package com.example.aiquizlite;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;

public class QuestionImageViewerActivity extends AppCompatActivity {
    public static final String EXTRA_QUESTION_BANK_ID = "question_bank_id";
    public static final String EXTRA_IMAGE_REFERENCE = "image_reference";
    public static final String EXTRA_KEEP_SCREEN_ON = "keep_screen_on";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout controls;
    private TextView gestureHint;
    private ZoomImageView zoomImage;
    private boolean controlsVisible = true;
    private final Runnable autoHideControls = () -> setControlsVisible(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureImmersiveWindow();
        if (getIntent().getBooleanExtra(EXTRA_KEEP_SCREEN_ON, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        String questionBankId = getIntent().getStringExtra(EXTRA_QUESTION_BANK_ID);
        String imageReference = getIntent().getStringExtra(EXTRA_IMAGE_REFERENCE);
        if (questionBankId == null || imageReference == null || imageReference.trim().isEmpty()) {
            finish();
            return;
        }

        Bitmap bitmap;
        try (InputStream inputStream = QuestionImageStore.open(this, questionBankId, imageReference)) {
            bitmap = BitmapFactory.decodeStream(inputStream);
        } catch (Exception error) {
            bitmap = null;
        }
        if (bitmap == null) {
            Toast.makeText(this, "无法打开题目图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF070A10);
        setContentView(root);

        zoomImage = new ZoomImageView(bitmap);
        zoomImage.setContentDescription("题目图片，可双指缩放，双击恢复适应屏幕");
        zoomImage.setOnSingleTapListener(() -> setControlsVisible(!controlsVisible));
        root.addView(zoomImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        controls = new FrameLayout(this);
        root.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView close = action("×", 28, 0xFF202633, Color.WHITE, 44, 44, 22);
        close.setContentDescription("关闭图片");
        close.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        closeParams.gravity = Gravity.START | Gravity.TOP;
        closeParams.leftMargin = dp(18);
        closeParams.topMargin = dp(18);
        controls.addView(close, closeParams);

        TextView reset = action("恢复适应屏幕", 13, 0xE6161B24, Color.WHITE, 0, 44, 14);
        reset.setPadding(dp(14), 0, dp(14), 0);
        reset.setContentDescription("恢复图片适应屏幕");
        reset.setOnClickListener(view -> {
            zoomImage.resetToFit();
            scheduleAutoHide();
        });
        FrameLayout.LayoutParams resetParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44)
        );
        resetParams.gravity = Gravity.END | Gravity.BOTTOM;
        resetParams.rightMargin = dp(18);
        resetParams.bottomMargin = dp(28);
        controls.addView(reset, resetParams);

        gestureHint = new TextView(this);
        gestureHint.setText("轻点画面隐藏控制\n双指缩放 · 双击复位");
        gestureHint.setTextSize(12);
        gestureHint.setTextColor(Color.WHITE);
        gestureHint.setGravity(Gravity.CENTER);
        gestureHint.setLineSpacing(dp(3), 1f);
        gestureHint.setPadding(dp(16), dp(10), dp(16), dp(10));
        gestureHint.setBackground(rounded(0xE61A202A, 16));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hintParams.gravity = Gravity.CENTER;
        root.addView(gestureHint, hintParams);

        gestureHint.animate().alpha(0f).setStartDelay(1600).setDuration(180)
                .withEndAction(() -> gestureHint.setVisibility(View.GONE)).start();
        scheduleAutoHide();
    }

    private void configureImmersiveWindow() {
        Window window = getWindow();
        window.setStatusBarColor(0xFF070A10);
        window.setNavigationBarColor(0xFF070A10);
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            configureImmersiveWindow();
        }
    }

    private void setControlsVisible(boolean visible) {
        handler.removeCallbacks(autoHideControls);
        controlsVisible = visible;
        controls.animate().cancel();
        if (visible) {
            controls.setVisibility(View.VISIBLE);
            controls.animate().alpha(1f).setDuration(160).start();
            scheduleAutoHide();
        } else {
            controls.animate().alpha(0f).setDuration(160)
                    .withEndAction(() -> {
                        if (!controlsVisible) {
                            controls.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private void scheduleAutoHide() {
        handler.removeCallbacks(autoHideControls);
        handler.postDelayed(autoHideControls, 2600);
    }

    private TextView action(
            String label,
            int textSize,
            int backgroundColor,
            int textColor,
            int widthDp,
            int heightDp,
            int radiusDp
    ) {
        TextView action = new TextView(this);
        action.setText(label);
        action.setTextSize(textSize);
        action.setTextColor(textColor);
        action.setGravity(Gravity.CENTER);
        action.setClickable(true);
        action.setFocusable(true);
        action.setBackground(rounded(backgroundColor, radiusDp));
        if (widthDp > 0) {
            action.setMinWidth(dp(widthDp));
        }
        action.setMinHeight(dp(heightDp));
        return action;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (zoomImage != null) {
            zoomImage.release();
        }
        super.onDestroy();
    }

    private final class ZoomImageView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix drawMatrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private Bitmap bitmap;
        private Runnable onSingleTapListener;
        private float fitScale = 1f;
        private float scale = 1f;
        private float translationX;
        private float translationY;

        ZoomImageView(Bitmap bitmap) {
            super(QuestionImageViewerActivity.this);
            this.bitmap = bitmap;
            setFocusable(true);
            setClickable(true);
            scaleDetector = new ScaleGestureDetector(
                    QuestionImageViewerActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            float nextScale = clamp(
                                    scale * detector.getScaleFactor(),
                                    fitScale,
                                    fitScale * 5f
                            );
                            float factor = nextScale / scale;
                            translationX = detector.getFocusX()
                                    - (detector.getFocusX() - translationX) * factor;
                            translationY = detector.getFocusY()
                                    - (detector.getFocusY() - translationY) * factor;
                            scale = nextScale;
                            constrainTranslation();
                            invalidate();
                            return true;
                        }
                    }
            );
            gestureDetector = new GestureDetector(
                    QuestionImageViewerActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent event) {
                            return true;
                        }

                        @Override
                        public boolean onScroll(
                                MotionEvent first,
                                MotionEvent current,
                                float distanceX,
                                float distanceY
                        ) {
                            if (scale <= fitScale + 0.001f) {
                                return false;
                            }
                            translationX -= distanceX;
                            translationY -= distanceY;
                            constrainTranslation();
                            invalidate();
                            return true;
                        }

                        @Override
                        public boolean onDoubleTap(MotionEvent event) {
                            resetToFit();
                            return true;
                        }

                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent event) {
                            ZoomImageView.this.performClick();
                            if (onSingleTapListener != null) {
                                onSingleTapListener.run();
                            }
                            return true;
                        }
                    }
            );
        }

        void setOnSingleTapListener(Runnable listener) {
            onSingleTapListener = listener;
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            resetToFit();
        }

        void resetToFit() {
            if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            fitScale = Math.min(
                    getWidth() / (float) bitmap.getWidth(),
                    getHeight() / (float) bitmap.getHeight()
            );
            scale = fitScale;
            translationX = (getWidth() - bitmap.getWidth() * scale) / 2f;
            translationY = (getHeight() - bitmap.getHeight() * scale) / 2f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) {
                return;
            }
            drawMatrix.reset();
            drawMatrix.postScale(scale, scale);
            drawMatrix.postTranslate(translationX, translationY);
            canvas.drawBitmap(bitmap, drawMatrix, paint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private void constrainTranslation() {
            if (bitmap == null) {
                return;
            }
            float scaledWidth = bitmap.getWidth() * scale;
            float scaledHeight = bitmap.getHeight() * scale;
            translationX = constrainAxis(translationX, scaledWidth, getWidth());
            translationY = constrainAxis(translationY, scaledHeight, getHeight());
        }

        private float constrainAxis(float translation, float contentSize, float viewportSize) {
            if (contentSize <= viewportSize) {
                return (viewportSize - contentSize) / 2f;
            }
            return clamp(translation, viewportSize - contentSize, 0f);
        }

        private float clamp(float value, float minimum, float maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        void release() {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            bitmap = null;
        }
    }
}
