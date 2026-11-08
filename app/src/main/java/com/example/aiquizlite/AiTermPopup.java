package com.example.aiquizlite;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** The term source is the displayed explanation's model, independent of an unsent model selection. */
final class AiTermPopup {
    private final Activity activity;
    private final AlertDialog parent;
    private final ScrollView explanationScroll;
    private final AiProfiles profiles;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger serial = new AtomicInteger();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::position;
    private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { close(); }
    };

    private PopupWindow popup;
    private LinearLayout popupContent;
    private TextView definition;
    private TextView analogy;
    private TextView analogyLabel;
    private TextView anchor;
    private AiTermMarkup.Range anchorRange;
    private Future<?> pending;
    private OpenAiCompatibleClient client;
    private boolean closed;

    AiTermPopup(Activity activity, AlertDialog parent, ScrollView explanationScroll) {
        this.activity = activity;
        this.parent = parent;
        this.explanationScroll = explanationScroll;
        profiles = new AiProfiles(activity.getApplicationContext());
        explanationScroll.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        explanationScroll.addOnAttachStateChangeListener(attachListener);
    }

    void bind(TextView view, String marked, AiProfiles.Model source, String explanationContext) {
        AiTermMarkup.Parsed parsed = AiTermMarkup.parse(marked);
        SpannableString styled = new SpannableString(parsed.text);
        if (source != null) {
            for (AiTermMarkup.Range range : parsed.ranges) {
                styled.setSpan(new StyleSpan(Typeface.BOLD), range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new ClickableSpan() {
                    @Override public void onClick(View clicked) {
                        show(view, range, source, explanationContext);
                    }

                    @Override public void updateDrawState(TextPaint paint) {
                        paint.setColor(activity.getColor(R.color.brand_primary_dark));
                        paint.setUnderlineText(false);
                    }
                }, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        view.setText(styled);
        view.setHighlightColor(Color.TRANSPARENT);
        view.setMovementMethod(source != null && !parsed.ranges.isEmpty() ? LinkMovementMethod.getInstance() : null);
    }

    private void show(TextView view, AiTermMarkup.Range range, AiProfiles.Model source, String context) {
        if (closed || !parent.isShowing()) return;
        dismiss();
        AiProfiles.Model latest = profiles.model(source.id);
        if (!available(latest, source)) {
            failed();
            return;
        }
        anchor = view;
        anchorRange = range;
        buildPopup(range.term);
        position();
        final int request = serial.incrementAndGet();
        final OpenAiCompatibleClient currentClient = new OpenAiCompatibleClient();
        client = currentClient;
        pending = executor.submit(() -> {
            try {
                AiProfiles.Model attempted = profiles.model(source.id);
                if (!available(attempted, source)) {
                    activity.runOnUiThread(() -> showFailure(request));
                    return;
                }
                String nearby = context == null ? "" : context;
                if (nearby.length() > 12000) nearby = nearby.substring(0, 12000);
                AiExplanation result = currentClient.generateExplanation(attempted.config,
                        "你是中文学科辅导老师。请结合给定讲解解释一个术语，用准确、通俗的语言和生活类比说明。"
                                + "只返回 JSON 对象，conclusion 为术语名，reasoning 为通俗解释，"
                                + "memory_tip 为生活类比，common_mistake 为空字符串。不得输出 Markdown、术语标记或额外文字。",
                        "待解释的术语：" + range.term + "\n所在讲解：\n" + nearby);
                activity.runOnUiThread(() -> {
                    if (!current(request)) return;
                    definition.setText(AiTermMarkup.parse(result.getReasoning().isEmpty()
                            ? result.toDisplayText() : result.getReasoning()).text);
                    String example = AiTermMarkup.parse(result.getMemoryTip()).text;
                    analogyLabel.setVisibility(example.isEmpty() ? View.GONE : View.VISIBLE);
                    analogy.setVisibility(example.isEmpty() ? View.GONE : View.VISIBLE);
                    analogy.setText(example);
                    position();
                });
            } catch (OpenAiCompatibleClient.AiClientException error) {
                if (request != serial.get() || error.isCancelled() || Thread.currentThread().isInterrupted()) return;
                if (error.shouldCooldown()) profiles.markFailed(latest, System.currentTimeMillis());
                activity.runOnUiThread(() -> showFailure(request));
            }
        });
    }

    private static boolean available(AiProfiles.Model model, AiProfiles.Model source) {
        return model != null && model.enabled && model.revision == source.revision
                && model.config.isComplete() && !model.isCooling(System.currentTimeMillis());
    }

    private boolean current(int request) {
        return !closed && request == serial.get() && popup != null && popup.isShowing()
                && parent.isShowing() && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void showFailure(int request) {
        if (!current(request)) return;
        dismiss();
        failed();
    }

    private void failed() {
        Toast.makeText(activity, R.string.ai_term_failed, Toast.LENGTH_SHORT).show();
    }

    private void buildPopup(String term) {
        int surfaceColor = Color.rgb(243, 246, 253);
        popupContent = new LinearLayout(activity);
        popupContent.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable surface = rounded(surfaceColor, 14);
        surface.setStroke(dp(1), activity.getColor(R.color.brand_primary));
        popupContent.setBackground(surface);
        popupContent.setPadding(dp(1), dp(1), dp(1), dp(1));
        popupContent.setClipToOutline(true);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(2), dp(3), dp(2));
        GradientDrawable headerBackground = rounded(Color.rgb(219, 231, 255), 0);
        float headerRadius = dp(13);
        headerBackground.setCornerRadii(new float[]{headerRadius, headerRadius, headerRadius, headerRadius, 0, 0, 0, 0});
        header.setBackground(headerBackground);
        TextView title = text(term, 15, R.color.brand_primary_dark, true);
        title.setMaxLines(2);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = text("×", 24, R.color.brand_text_secondary, false);
        close.setContentDescription(activity.getString(R.string.ai_term_close));
        close.setGravity(Gravity.CENTER);
        close.setBackgroundResource(android.R.drawable.list_selector_background);
        close.setOnClickListener(clicked -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        popupContent.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(12), dp(14), dp(14));
        TextView definitionLabel = text(activity.getString(R.string.ai_term_definition), 12,
                R.color.brand_primary_dark, true);
        body.addView(definitionLabel);
        definition = text(activity.getString(R.string.ai_term_loading), 14, R.color.brand_text_secondary, false);
        definition.setPadding(0, dp(4), 0, 0);
        definition.setLineSpacing(dp(2), 1);
        body.addView(definition, new LinearLayout.LayoutParams(-1, -2));
        analogyLabel = text(activity.getString(R.string.ai_term_analogy), 12, R.color.brand_primary_dark, true);
        analogyLabel.setPadding(0, dp(12), 0, 0);
        analogyLabel.setVisibility(View.GONE);
        body.addView(analogyLabel);
        analogy = text("", 14, R.color.brand_text_secondary, false);
        analogy.setPadding(0, dp(4), 0, 0);
        analogy.setLineSpacing(dp(2), 1);
        analogy.setVisibility(View.GONE);
        body.addView(analogy, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(body);
        popupContent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        popup = new PopupWindow(popupContent, dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // An opaque background supplies the rounded outline used by the window's elevation shadow.
        popup.setBackgroundDrawable(rounded(surfaceColor, 14));
        // The dialog card closes the popup on an outside tap, preserving outside drag gestures.
        popup.setOutsideTouchable(false);
        popup.setElevation(dp(14));
        popup.setAnimationStyle(android.R.style.Animation_Dialog);
        PopupWindow created = popup;
        popup.setOnDismissListener(() -> {
            if (popup == created) clearRequest();
        });
    }

    private void position() {
        if (closed || popup == null || anchor == null || anchor.getLayout() == null || parent.getWindow() == null) return;
        View decor = parent.getWindow().getDecorView();
        if (!decor.isAttachedToWindow() || decor.getWidth() == 0 || decor.getHeight() == 0) return;
        int[] dialogPosition = new int[2];
        decor.getLocationInWindow(dialogPosition);
        Rect bounds = new Rect(dialogPosition[0], dialogPosition[1], dialogPosition[0] + decor.getWidth(),
                dialogPosition[1] + decor.getHeight());
        Rect screen = new Rect();
        decor.getWindowVisibleDisplayFrame(screen);
        int[] screenPosition = new int[2];
        decor.getLocationOnScreen(screenPosition);
        screen.offset(dialogPosition[0] - screenPosition[0], dialogPosition[1] - screenPosition[1]);
        if (!bounds.intersect(screen)) return;

        Layout textLayout = anchor.getLayout();
        int line = textLayout.getLineForOffset(anchorRange.start);
        int[] textPosition = new int[2];
        anchor.getLocationInWindow(textPosition);
        int top = textPosition[1] + anchor.getTotalPaddingTop() - anchor.getScrollY() + textLayout.getLineTop(line);
        int bottom = textPosition[1] + anchor.getTotalPaddingTop() - anchor.getScrollY() + textLayout.getLineBottom(line);
        Rect visible = new Rect();
        boolean onScreen = explanationScroll.getGlobalVisibleRect(visible);
        int[] rootPosition = new int[2];
        explanationScroll.getRootView().getLocationInWindow(rootPosition);
        visible.offset(rootPosition[0], rootPosition[1]);
        onScreen = onScreen && top < visible.bottom && bottom > visible.top;
        popupContent.setVisibility(onScreen ? View.VISIBLE : View.INVISIBLE);
        popup.setTouchable(onScreen);
        if (!onScreen) {
            if (popup.isShowing()) popup.update();
            return;
        }
        int width = Math.min(dp(280), bounds.width() - dp(20));
        int maximumHeight = Math.max(dp(1), Math.round(decor.getHeight() * 0.4f));
        popupContent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maximumHeight, View.MeasureSpec.AT_MOST));
        int height = Math.min(maximumHeight, popupContent.getMeasuredHeight());
        int textX = textPosition[0] + anchor.getTotalPaddingLeft()
                + Math.round(textLayout.getPrimaryHorizontal(anchorRange.start));
        int x = Math.max(bounds.left + dp(10), Math.min(textX - dp(12), bounds.right - width - dp(10)));
        int desiredY = bottom + dp(6);
        if (desiredY + height > bounds.bottom - dp(8)) desiredY = top - height - dp(6);
        int y = Math.max(bounds.top + dp(8), Math.min(desiredY, bounds.bottom - height - dp(8)));
        if (popup.isShowing()) popup.update(x, y, width, height);
        else {
            popup.setWidth(width);
            popup.setHeight(height);
            popup.showAtLocation(decor, Gravity.TOP | Gravity.LEFT, x, y);
        }
    }

    void dismiss() {
        PopupWindow previous = popup;
        clearRequest();
        if (previous != null && previous.isShowing()) previous.dismiss();
    }

    private void clearRequest() {
        serial.incrementAndGet();
        if (pending != null) pending.cancel(true);
        if (client != null) client.cancel();
        pending = null;
        client = null;
        popup = null;
        popupContent = null;
        definition = null;
        analogy = null;
        analogyLabel = null;
        anchor = null;
        anchorRange = null;
    }

    void close() {
        if (closed) return;
        closed = true;
        dismiss();
        if (explanationScroll.getViewTreeObserver().isAlive()) {
            explanationScroll.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        }
        explanationScroll.removeOnAttachStateChangeListener(attachListener);
        executor.shutdownNow();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(activity.getColor(color));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

}
