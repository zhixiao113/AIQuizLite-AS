package com.example.aiquizlite;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.transition.Fade;
import android.transition.Slide;
import android.transition.TransitionSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Rebuilds the dropdown from the same live pool used by the explanation router. */
final class AiExplanationModelMenu {
    private final Activity activity;
    private final AlertDialog dialog;
    private final View anchor;
    private final AiProfiles profiles;
    private final boolean needsVision;
    private final Supplier<String> selection;
    private final Supplier<String> contentSource;
    private final Consumer<AiProfiles.Model> choose;
    private PopupWindow popup;

    AiExplanationModelMenu(Activity activity, AlertDialog dialog, View anchor, AiProfiles profiles,
            boolean needsVision, Supplier<String> selection, Supplier<String> contentSource,
            Consumer<AiProfiles.Model> choose) {
        this.activity = activity;
        this.dialog = dialog;
        this.anchor = anchor;
        this.profiles = profiles;
        this.needsVision = needsVision;
        this.selection = selection;
        this.contentSource = contentSource;
        this.choose = choose;
    }

    void toggle() {
        if (popup != null && popup.isShowing()) {
            dismiss();
            return;
        }
        List<AiProfiles.Model> models = eligible();
        if (models.size() <= 1) {
            Toast.makeText(activity, needsVision ? R.string.ai_dialog_more_vision_unavailable
                    : R.string.ai_dialog_more_models_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        show(models);
    }

    void refresh() {
        if (popup == null || !popup.isShowing()) return;
        List<AiProfiles.Model> models = eligible();
        dismiss();
        if (models.size() > 1 && dialog.isShowing()) show(models);
    }

    void dismiss() {
        if (popup != null) popup.dismiss();
        popup = null;
    }

    private List<AiProfiles.Model> eligible() {
        return AiExplanationRouter.eligibleModels(profiles, needsVision, System.currentTimeMillis());
    }

    private void show(List<AiProfiles.Model> models) {
        if (!dialog.isShowing() || dialog.getWindow() == null) return;
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(12), dp(8), dp(8));
        panel.setBackgroundResource(R.drawable.bg_ai_model_menu);
        TextView title = text(activity.getString(R.string.ai_dialog_choose_model), 14, R.color.brand_ink);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(8), 0, dp(8), 0);
        panel.addView(title);
        TextView hint = text(activity.getString(R.string.ai_dialog_menu_order, models.size()), 11, R.color.brand_text_secondary);
        hint.setPadding(dp(8), dp(4), dp(8), dp(10));
        panel.addView(hint);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (AiProfiles.Model model : models) rows.addView(row(model));
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        View root = dialog.getWindow().getDecorView();
        int width = Math.min(dp(288), root.getWidth() - dp(24));
        int maxHeight = Math.round(root.getHeight() * 0.6f);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        int height = Math.min(panel.getMeasuredHeight(), maxHeight);
        popup = new PopupWindow(panel, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(10));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        TransitionSet enter = new TransitionSet().addTransition(new Fade()).addTransition(new Slide(Gravity.TOP));
        enter.setDuration(130);
        popup.setEnterTransition(enter);
        popup.setExitTransition(new Fade().setDuration(100));
        // Align the panel's trailing edge with the icon and let the window keep it on screen.
        popup.showAsDropDown(anchor, anchor.getWidth() - width, 0);
    }

    private View row(AiProfiles.Model model) {
        boolean selected = model.id.equals(selection.get());
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setMinimumHeight(dp(56));
        row.setBackgroundResource(R.drawable.bg_ai_model_menu_row);
        row.setSelected(selected);
        row.setFocusable(true);
        row.setClickable(true);
        if (selected) {
            View stripe = new View(activity);
            stripe.setBackgroundColor(activity.getColor(R.color.brand_primary));
            LinearLayout.LayoutParams stripeSize = new LinearLayout.LayoutParams(dp(3), dp(30));
            stripeSize.setMarginEnd(dp(6));
            row.addView(stripe, stripeSize);
        }
        ImageView icon = new ImageView(activity);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setBackgroundResource(R.drawable.bg_ai_model_ring);
        icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        icon.setClipToOutline(true);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        AiModelIcons.bind(icon, model.config.getModel(), false);
        row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, dp(5), 0);
        TextView name = text(model.config.getModel(), 13, selected ? R.color.brand_primary_dark : R.color.brand_ink);
        name.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        labels.addView(name);
        String description = AiModelIcons.match(model.config.getModel()).label + " · "
                + activity.getString(model.visionSupported ? R.string.ai_dialog_menu_vision : R.string.ai_dialog_menu_text);
        TextView capability = text(description, 11, R.color.brand_text_secondary);
        labels.addView(capability);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (selected) {
            TextView check = text(activity.getString(model.id.equals(contentSource.get())
                    ? R.string.ai_dialog_menu_current : R.string.ai_dialog_menu_selected), 11, R.color.brand_primary_dark);
            row.addView(check);
        }
        row.setOnClickListener(view -> {
            dismiss();
            if (!selected) choose.accept(model);
        });
        return row;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(activity.getColor(color));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
