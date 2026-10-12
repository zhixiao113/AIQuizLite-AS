package com.example.aiquizlite;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Checkable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;

public class GeneralSettingsActivity extends AppCompatActivity {
    private GeneralSettingsStore settings;
    private ActivityResultLauncher<Uri> folderPicker;
    private TextView displayPreviewState;
    private TextView displayPreviewQuestion;
    private TextView displayPreviewOptionA;
    private TextView displayPreviewOptionB;
    private AlertDialog updateCheckDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new GeneralSettingsStore(this);
        folderPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                this::handleSelectedFolder
        );
        getWindow().setStatusBarColor(getColor(R.color.brand_surface));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildLayout();
    }

    private void buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.brand_surface));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(content, matchWrap());
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, matchWrap());

        TextView back = text("‹", 38, R.color.brand_primary, false);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setBackground(rounded(0xFFEEF4FF, 16));
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(14);
        header.addView(titles, titleParams);
        titles.addView(text("设置", 28, R.color.brand_ink, true), matchWrap());
        TextView subtitle = text("专注练习、考试与本地题库管理", 13, R.color.brand_text_secondary, false);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle, matchWrap());

        addSectionTitle(content, "学习与刷题");
        LinearLayout practice = sectionCard(content);
        addSwitchRow(practice, R.drawable.ic_general_practice,
                "续刷上次练习", "回到日常刷题时继续上次未完成的位置",
                settings.shouldResumeDailyPractice(), settings::setResumeDailyPractice);
        addDivider(practice);
        addSwitchRow(practice, R.drawable.ic_general_practice,
                getString(R.string.daily_review_setting), getString(R.string.daily_review_setting_detail),
                settings.shouldReviewDailyHistory(), enabled -> {
                    if (!enabled) {
                        settings.setReviewDailyHistory(false);
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.daily_review_enable_title)
                            .setMessage(R.string.daily_review_enable_message)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.daily_review_enable, (dialog, which) ->
                                    settings.setReviewDailyHistory(true))
                            .setOnDismissListener(dialog -> buildLayout())
                            .show();
                });
        addDivider(practice);
        addSwitchRow(practice, R.drawable.ic_general_wrong,
                "自动加入错题本", "答错后自动加入；关闭后只显示本次反馈",
                settings.shouldAutoAddWrongQuestion(), settings::setAutoAddWrongQuestion);
        addDivider(practice);
        addChoiceRow(practice, R.drawable.ic_general_wrong,
                "错题移除条件", "控制错题何时自动移出错题本",
                GeneralSettingsRules.wrongRemovalLabel(settings.getWrongRemovalThreshold()),
                view -> showWrongRemovalChoices());
        addDivider(practice);
        addSwitchRow(practice, R.drawable.ic_general_practice,
                "答对后自动下一题", "提交正确答案后按设定等待时间继续",
                settings.shouldAutoAdvanceAfterCorrect(), enabled -> {
                    boolean restoresDelay = enabled && settings.getAutoAdvanceDelaySeconds()
                            == GeneralSettingsRules.AUTO_ADVANCE_DISABLED;
                    settings.setAutoAdvanceAfterCorrect(enabled);
                    if (restoresDelay) {
                        buildLayout();
                    }
                });
        addDivider(practice);
        addSwitchRow(practice, R.drawable.ic_general_practice,
                "答错后自动下一题", "默认关闭，便于查看正确答案与纠错",
                settings.shouldAutoAdvanceAfterWrong(), enabled -> {
                    boolean restoresDelay = enabled && settings.getAutoAdvanceDelaySeconds()
                            == GeneralSettingsRules.AUTO_ADVANCE_DISABLED;
                    settings.setAutoAdvanceAfterWrong(enabled);
                    if (restoresDelay) {
                        buildLayout();
                    }
                });
        addDivider(practice);
        addChoiceRow(practice, R.drawable.ic_general_practice,
                "自动跳题等待", "选择不启用时同时关闭两个自动下一题开关",
                GeneralSettingsRules.autoAdvanceDelayLabel(settings.getAutoAdvanceDelaySeconds()),
                view -> showDelayChoices());
        addDivider(practice);
        addSwitchRow(practice, R.drawable.ic_general_info,
                "提交后显示题库解析", "隐藏时仍显示作答结果、正确答案和 AI 讲解入口",
                settings.shouldShowSourceExplanation(), settings::setShowSourceExplanation);

        addSectionTitle(content, "显示与交互");
        addDisplayInteractionSection(content);

        addSectionTitle(content, "模拟考试");
        LinearLayout mock = sectionCard(content);
        addChoiceRow(mock, R.drawable.ic_general_exam,
                "默认考试题量", "题量不足时使用当前题库全部客观题",
                GeneralSettingsRules.mockQuestionLimitLabel(settings.getMockQuestionLimit()),
                view -> showMockCountChoices());
        addDivider(mock);
        addSwitchRow(mock, R.drawable.ic_general_exam,
                "打乱题序", "每次新建模拟考试时重新排列题目",
                settings.shouldShuffleMockQuestions(), settings::setShuffleMockQuestions);
        addDivider(mock);
        addSwitchRow(mock, R.drawable.ic_general_exam,
                "智能打乱选项", "仅打乱安全题目的选项内容，显示字母仍为 A、B、C、D",
                settings.shouldShuffleMockOptions(), settings::setShuffleMockOptions);
        addDivider(mock);
        addSwitchRow(mock, R.drawable.ic_general_exam,
                "交卷前提醒未作答", "存在空题时先确认，避免误交卷",
                settings.shouldWarnBeforeSubmittingUnansweredMock(),
                settings::setWarnBeforeSubmittingUnansweredMock);

        addSectionTitle(content, "文件与下载");
        LinearLayout files = sectionCard(content);
        addChoiceRow(files, R.drawable.ic_general_folder,
                "下载库位置", "导出的题库 ZIP 默认保存位置",
                settings.getDownloadDirectoryLabel(), view -> showDownloadFolderChoices());
        addDivider(files);
        addSwitchRow(files, R.drawable.ic_general_folder,
                "显示手动放入的 ZIP", "扫描当前下载目录中的 ZIP，并按最新时间显示",
                settings.shouldDiscoverManualZip(), settings::setDiscoverManualZip);
        addDivider(files);
        addChoiceRow(files, R.drawable.ic_download_library,
                "下载库与存储", "查看、分享、导入或删除题库包",
                "打开", view -> startActivity(new Intent(this, DownloadLibraryActivity.class)));
        addDivider(files);
        addChoiceRow(files, R.drawable.ic_general_folder,
                "备份与数据", "完整备份题库、图片、学习记录和普通设置",
                "安全中心", view -> startActivity(new Intent(this, BackupDataActivity.class)));

        addSectionTitle(content, "关于");
        LinearLayout about = sectionCard(content);
        addChoiceRow(about, R.drawable.ic_general_info,
                "AIQuizLite", "为个人学习与备考设计的本地题库练习工具",
                BuildConfig.VERSION_NAME, null);
        addDivider(about);
        addChoiceRow(about, R.drawable.ic_general_update,
                "检查更新", "检测新版本，下载并校验后由系统确认安装",
                "检查", view -> checkForUpdates());
    }

    private void checkForUpdates() {
        if (updateCheckDialog != null) { return; }
        final String selectedSource = settings.getUpdateServerUrl();
        AlertDialog checking = new AlertDialog.Builder(this)
                .setTitle("正在检查更新")
                .setMessage("正在连接更新服务器…")
                .setCancelable(false)
                .show();
        updateCheckDialog = checking;
        UpdateChecker.checkUpdate(this, selectedSource, new UpdateChecker.Callback<UpdateChecker.UpdateInfo>() {
            @Override
            public void onResult(UpdateChecker.UpdateInfo info) {
                updateCheckDialog = null;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (checking.isShowing()) {
                    checking.dismiss();
                }
                if (UpdateChecker.hasNewVersion(GeneralSettingsActivity.this, info)) {
                    UpdateDialog.startUpdateFlow(GeneralSettingsActivity.this,
                            selectedSource, info);
                } else {
                    Toast.makeText(GeneralSettingsActivity.this,
                            "当前已是最新版本 v" + BuildConfig.VERSION_NAME, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                updateCheckDialog = null;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (checking.isShowing()) {
                    checking.dismiss();
                }
                Toast.makeText(GeneralSettingsActivity.this,
                        "检查更新失败：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (updateCheckDialog != null) { updateCheckDialog.dismiss(); updateCheckDialog = null; }
        super.onDestroy();
    }

    private void addDisplayInteractionSection(LinearLayout content) {
        MaterialCardView previewCard = new MaterialCardView(this);
        previewCard.setCardBackgroundColor(0xFFEEF4FF);
        previewCard.setCardElevation(0);
        previewCard.setRadius(dp(18));
        previewCard.setStrokeWidth(dp(1));
        previewCard.setStrokeColor(0xFFDBE5FB);
        content.addView(previewCard, matchWrap());

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(16), dp(14), dp(16), dp(16));
        previewCard.addView(preview, matchWrap());

        LinearLayout previewHeader = new LinearLayout(this);
        previewHeader.setGravity(Gravity.CENTER_VERTICAL);
        preview.addView(previewHeader, matchWrap());
        previewHeader.addView(text("答题页预览", 14, R.color.brand_ink, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        displayPreviewState = text("", 12, R.color.brand_primary, true);
        displayPreviewState.setGravity(Gravity.END);
        previewHeader.addView(displayPreviewState, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        displayPreviewQuestion = text(
                "3. 操作系统负责管理计算机的软硬件资源。",
                16,
                R.color.brand_ink,
                true
        );
        displayPreviewQuestion.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams questionParams = matchWrap();
        questionParams.topMargin = dp(15);
        preview.addView(displayPreviewQuestion, questionParams);

        displayPreviewOptionA = previewOption("A. 对");
        displayPreviewOptionB = previewOption("B. 错");
        preview.addView(displayPreviewOptionA, previewOptionParams(13));
        preview.addView(displayPreviewOptionB, previewOptionParams(8));

        LinearLayout controls = sectionCard(content);
        View controlsCard = (View) controls.getParent();
        LinearLayout.LayoutParams controlsParams = (LinearLayout.LayoutParams) controlsCard.getLayoutParams();
        controlsParams.topMargin = dp(12);
        controlsCard.setLayoutParams(controlsParams);

        addSegmentedSettingBlock(
                controls,
                "题目字体",
                "题干、选项和连线文字同步调整",
                settings.getQuestionFontMode(),
                new String[]{"较小", "标准", "较大"},
                new int[]{GeneralSettingsRules.QUESTION_FONT_SMALL,
                        GeneralSettingsRules.QUESTION_FONT_STANDARD, GeneralSettingsRules.QUESTION_FONT_LARGE},
                value -> {
                    settings.setQuestionFontMode(value);
                    refreshDisplayPreview();
                }
        );
        addCompactDivider(controls);
        addSegmentedSettingBlock(
                controls,
                "页面密度",
                "紧凑模式减少间距，不缩小触控区域",
                settings.getQuizDensity(),
                new String[]{"标准", "紧凑"},
                new int[]{GeneralSettingsRules.QUIZ_DENSITY_STANDARD, GeneralSettingsRules.QUIZ_DENSITY_COMPACT},
                value -> {
                    settings.setQuizDensity(value);
                    refreshDisplayPreview();
                }
        );
        addCompactDivider(controls);
        addCompactSwitchBlock(
                controls,
                "图片全屏与缩放",
                "点击题目图片后支持双指缩放和双击复位",
                settings.shouldOpenImagesFullscreen(),
                settings::setOpenImagesFullscreen
        );
        addCompactDivider(controls);
        addCompactSwitchBlock(
                controls,
                "操作反馈",
                "选择、收藏和提交时轻微震动",
                settings.shouldUseHapticFeedback(),
                settings::setUseHapticFeedback
        );
        addCompactDivider(controls);
        addCompactSwitchBlock(
                controls,
                "考试保持常亮",
                "仅模拟考试进行时生效",
                settings.shouldKeepScreenOnDuringMock(),
                settings::setKeepScreenOnDuringMock
        );
        refreshDisplayPreview();
    }

    private TextView previewOption(String label) {
        TextView option = text(label, 14, R.color.brand_ink, false);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setBackground(outlinedRounded(Color.WHITE, 0xFFDBE5FB, 12));
        return option;
    }

    private LinearLayout.LayoutParams previewOptionParams(int topMarginDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private void refreshDisplayPreview() {
        if (displayPreviewState == null) {
            return;
        }
        int fontMode = settings.getQuestionFontMode();
        boolean compact = settings.getQuizDensity() == GeneralSettingsRules.QUIZ_DENSITY_COMPACT;
        displayPreviewState.setText(getString(
                R.string.display_preview_state,
                GeneralSettingsRules.questionFontLabel(settings.getQuestionFontMode()),
                GeneralSettingsRules.quizDensityLabel(settings.getQuizDensity())
        ));
        displayPreviewQuestion.setTextSize(GeneralSettingsRules.questionStemSizeSp(fontMode) - 2);
        displayPreviewOptionA.setTextSize(GeneralSettingsRules.questionOptionSizeSp(fontMode) - 2);
        displayPreviewOptionB.setTextSize(GeneralSettingsRules.questionOptionSizeSp(fontMode) - 2);
        int verticalPadding = dp(compact ? 7 : 10);
        displayPreviewOptionA.setPadding(dp(12), verticalPadding, dp(12), verticalPadding);
        displayPreviewOptionB.setPadding(dp(12), verticalPadding, dp(12), verticalPadding);
        LinearLayout.LayoutParams questionParams = (LinearLayout.LayoutParams) displayPreviewQuestion.getLayoutParams();
        questionParams.topMargin = dp(compact ? 10 : 15);
        displayPreviewQuestion.setLayoutParams(questionParams);
        LinearLayout.LayoutParams firstOptionParams = (LinearLayout.LayoutParams) displayPreviewOptionA.getLayoutParams();
        firstOptionParams.topMargin = dp(compact ? 9 : 13);
        displayPreviewOptionA.setLayoutParams(firstOptionParams);
        LinearLayout.LayoutParams secondOptionParams = (LinearLayout.LayoutParams) displayPreviewOptionB.getLayoutParams();
        secondOptionParams.topMargin = dp(compact ? 6 : 8);
        displayPreviewOptionB.setLayoutParams(secondOptionParams);
    }

    private void addSegmentedSettingBlock(
            LinearLayout parent,
            String title,
            String description,
            int selectedValue,
            String[] labels,
            int[] values,
            IntConsumer onChange
    ) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(16), dp(14), dp(14), dp(14));
        parent.addView(block, matchWrap());

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        block.addView(top, matchWrap());
        top.addView(text(title, 16, R.color.brand_ink, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout segment = new LinearLayout(this);
        segment.setPadding(dp(3), dp(3), dp(3), dp(3));
        segment.setBackground(rounded(0xFFF3F4F6, 12));
        top.addView(segment, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(50)
        ));
        TextView[] choices = new TextView[labels.length];
        for (int index = 0; index < labels.length; index++) {
            int value = values[index];
            TextView choice = text(labels[index], 13,
                    value == selectedValue ? R.color.brand_card : R.color.brand_text_secondary,
                    true);
            choice.setGravity(Gravity.CENTER);
            choice.setMinWidth(dp(labels.length > 2 ? 50 : 62));
            choice.setBackground(rounded(
                    value == selectedValue ? getColor(R.color.brand_primary) : Color.TRANSPARENT,
                    9
            ));
            choice.setClickable(true);
            choice.setFocusable(true);
            choice.setContentDescription(title + "，" + labels[index]);
            choice.setOnClickListener(view -> {
                onChange.accept(value);
                for (int choiceIndex = 0; choiceIndex < choices.length; choiceIndex++) {
                    boolean selected = values[choiceIndex] == value;
                    choices[choiceIndex].setTextColor(getColor(
                            selected ? R.color.brand_card : R.color.brand_text_secondary
                    ));
                    choices[choiceIndex].setBackground(rounded(
                            selected ? getColor(R.color.brand_primary) : Color.TRANSPARENT,
                            9
                    ));
                }
            });
            choices[index] = choice;
            segment.addView(choice, new LinearLayout.LayoutParams(
                    dp(labels.length > 2 ? 52 : 64), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        TextView help = text(description, 12, R.color.brand_text_secondary, false);
        help.setPadding(0, dp(5), 0, 0);
        block.addView(help, matchWrap());
    }

    private void addCompactSwitchBlock(
            LinearLayout parent,
            String title,
            String description,
            boolean checked,
            BooleanConsumer onChange
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(12), dp(11));
        row.setMinimumHeight(dp(76));
        parent.addView(row, matchWrap());

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        labelParams.rightMargin = dp(8);
        row.addView(labels, labelParams);
        labels.addView(text(title, 16, R.color.brand_ink, true), matchWrap());
        TextView detail = text(description, 12, R.color.brand_text_secondary, false);
        detail.setPadding(0, dp(3), 0, 0);
        labels.addView(detail, matchWrap());

        SettingsToggleView toggle = new SettingsToggleView(checked, title, onChange);
        row.addView(toggle, new LinearLayout.LayoutParams(dp(54), dp(48)));
        row.setOnClickListener(view -> toggle.toggle());
    }

    private void addCompactDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE5E7EB);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.leftMargin = dp(16);
        params.rightMargin = dp(16);
        parent.addView(divider, params);
    }

    private void showWrongRemovalChoices() {
        String[] labels = {"答对 1 次", "连续答对 2 次", "连续答对 3 次", "仅手动移除"};
        int[] values = {1, 2, 3, GeneralSettingsRules.WRONG_REMOVAL_MANUAL};
        int checked = indexOf(values, settings.getWrongRemovalThreshold());
        new AlertDialog.Builder(this)
                .setTitle("错题移除条件")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    settings.setWrongRemovalThreshold(values[which]);
                    dialog.dismiss();
                    buildLayout();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDelayChoices() {
        String[] labels = {"不启用", "1 秒", "2 秒", "3 秒"};
        int[] values = {GeneralSettingsRules.AUTO_ADVANCE_DISABLED, 1, 2, 3};
        int checked = indexOf(values, settings.getAutoAdvanceDelaySeconds());
        new AlertDialog.Builder(this)
                .setTitle("自动跳题等待")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    settings.setAutoAdvanceDelaySeconds(values[which]);
                    dialog.dismiss();
                    buildLayout();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMockCountChoices() {
        String[] labels = {"20 题", "50 题", "100 题", "全部客观题"};
        int[] values = {20, 50, 100, GeneralSettingsRules.MOCK_ALL_QUESTIONS};
        int checked = indexOf(values, settings.getMockQuestionLimit());
        new AlertDialog.Builder(this)
                .setTitle("默认考试题量")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    settings.setMockQuestionLimit(values[which]);
                    dialog.dismiss();
                    buildLayout();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDownloadFolderChoices() {
        new AlertDialog.Builder(this)
                .setTitle("下载库位置")
                .setItems(new String[]{"选择自定义文件夹", "恢复默认位置 Download/AIQuizLite"},
                        (dialog, which) -> {
                            if (which == 0) {
                                folderPicker.launch(settings.getDownloadTreeUri());
                            } else {
                                settings.clearDownloadTree();
                                buildLayout();
                                Toast.makeText(this, "已恢复默认下载位置", Toast.LENGTH_SHORT).show();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void handleSelectedFolder(Uri treeUri) {
        if (treeUri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
            settings.setDownloadTree(treeUri, resolveFolderLabel(treeUri));
            buildLayout();
            Toast.makeText(this, "后续导出的 ZIP 将保存到所选文件夹", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "无法长期访问这个文件夹，请重新选择", Toast.LENGTH_LONG).show();
        }
    }

    private String resolveFolderLabel(Uri treeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            try (Cursor cursor = getContentResolver().query(
                    documentUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null,
                    null,
                    null
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.trim().isEmpty()) {
                        return "自定义 · " + name.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "自定义文件夹";
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView label = text(title, 14, R.color.brand_text_secondary, true);
        label.setPadding(dp(4), dp(28), 0, dp(10));
        parent.addView(label, matchWrap());
    }

    private LinearLayout sectionCard(LinearLayout parent) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(Color.WHITE);
        card.setCardElevation(0);
        card.setRadius(dp(18));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0xFFE5E7EB);
        parent.addView(card, matchWrap());
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        card.addView(rows, matchWrap());
        return rows;
    }

    private void addSwitchRow(
            LinearLayout parent,
            int iconRes,
            String title,
            String description,
            boolean checked,
            BooleanConsumer onChange
    ) {
        LinearLayout row = baseRow(iconRes, title, description);
        SettingsToggleView toggle = new SettingsToggleView(checked, title, onChange);
        row.addView(toggle, new LinearLayout.LayoutParams(dp(54), dp(48)));
        row.setOnClickListener(view -> toggle.toggle());
        parent.addView(row, matchWrap());
    }

    private void addChoiceRow(
            LinearLayout parent,
            int iconRes,
            String title,
            String description,
            String value,
            View.OnClickListener listener
    ) {
        LinearLayout row = baseRow(iconRes, title, description);
        TextView valueText = text(value + (listener == null ? "" : "  ›"), 13, R.color.brand_primary, true);
        valueText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        valueText.setMaxWidth(dp(132));
        row.addView(valueText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        if (listener != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(listener);
        }
        parent.addView(row, matchWrap());
    }

    private LinearLayout baseRow(int iconRes, String title, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(76));

        FrameLayout iconTile = new FrameLayout(this);
        iconTile.setBackground(rounded(0xFFEEF4FF, 14));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        iconTile.addView(icon, iconParams);
        row.addView(iconTile, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsParams.leftMargin = dp(12);
        labelsParams.rightMargin = dp(8);
        row.addView(labels, labelsParams);
        labels.addView(text(title, 16, R.color.brand_ink, true), matchWrap());
        TextView detail = text(description, 12, R.color.brand_text_secondary, false);
        detail.setLineSpacing(0, 1.08f);
        detail.setPadding(0, dp(3), 0, 0);
        labels.addView(detail, matchWrap());
        return row;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE5E7EB);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.leftMargin = dp(72);
        parent.addView(divider, params);
    }

    private TextView text(String value, int sp, int colorRes, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(getColor(colorRes));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable outlinedRounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int indexOf(int[] values, int expected) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == expected) {
                return index;
            }
        }
        return 0;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface BooleanConsumer {
        void accept(boolean value);
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private final class SettingsToggleView extends View implements Checkable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final BooleanConsumer onChange;
        private boolean checked;

        SettingsToggleView(boolean checked, String description, BooleanConsumer onChange) {
            super(GeneralSettingsActivity.this);
            this.checked = checked;
            this.onChange = onChange;
            setClickable(true);
            setFocusable(true);
            setContentDescription(description);
            setLayerType(LAYER_TYPE_SOFTWARE, paint);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float trackWidth = dp(48);
            float trackHeight = dp(28);
            float left = (getWidth() - trackWidth) / 2f;
            float top = (getHeight() - trackHeight) / 2f;
            float radius = trackHeight / 2f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? getColor(R.color.brand_primary) : 0xFFCBD5E1);
            canvas.drawRoundRect(left, top, left + trackWidth, top + trackHeight, radius, radius, paint);

            float thumbRadius = dp(11);
            float thumbCenterX = checked
                    ? left + trackWidth - radius
                    : left + radius;
            float thumbCenterY = top + radius;
            paint.setShadowLayer(dp(2), 0, dp(1), 0x33000000);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(thumbCenterX, thumbCenterY, thumbRadius, paint);
            paint.clearShadowLayer();
        }

        @Override
        public boolean performClick() {
            toggle();
            return super.performClick();
        }

        @Override
        public void setChecked(boolean checked) {
            if (this.checked == checked) {
                return;
            }
            this.checked = checked;
            onChange.accept(checked);
            refreshDrawableState();
            invalidate();
        }

        @Override
        public boolean isChecked() {
            return checked;
        }

        @Override
        public void toggle() {
            setChecked(!checked);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }
}
