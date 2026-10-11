package com.example.aiquizlite;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MaterialToQuizActivity extends AppCompatActivity {
    public static final String EXTRA_CREATED_BANK_ID = "created_bank_id";

    private enum Stage {
        WELCOME,
        PROCESSING,
        TASK_ERROR,
        PLAN,
        GENERATING,
        PREVIEW
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String[]> documentPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::processDocument);
    private final List<ChapterChoice> chapterChoices = new ArrayList<>();
    private final Map<String, Integer> typeQuotas = new LinkedHashMap<>();
    private final MaterialImportTaskManager.Listener importTaskListener =
            this::handleImportTaskSnapshot;

    private MaterialDraftStore draftStore;
    private MaterialImportTaskManager importTaskManager;
    private MaterialDocumentExtractor.DocumentData document;
    private MaterialGenerationPlan plan;
    private MaterialQuestionGenerator.GenerationResult generationResult;
    private MaterialQuestionGenerator.Snapshot lastSnapshot;
    private JSONArray questions = new JSONArray();
    private JSONArray unresolvedQuestions = new JSONArray();
    private Stage stage = Stage.WELCOME;
    private String bankName = "";
    private boolean automaticAttemptsExhausted;
    private boolean suppressDraftSave;
    private boolean convertedQuestionBank;
    private boolean imageRolesConfirmed;
    private boolean openPickerAfterAiSetup;
    private android.net.Uri pendingDocumentUri;
    private LinearLayout content;
    private LinearLayout footer;
    private TextView planTotalsText;
    private long renderedImportTaskId = -1L;
    private TextView processingMessageText;
    private TextView processingProgressText;
    private TextView processingHintText;
    private final TextView[] processingStepTexts = new TextView[3];
    private ProgressBar processingProgress;
    private String[] processingTips = new String[0];
    private int processingTipIndex;
    private final Runnable rotateProcessingTip = new Runnable() {
        @Override
        public void run() {
            if (stage != Stage.PROCESSING
                    || processingHintText == null
                    || processingTips.length == 0) {
                return;
            }
            processingTipIndex = (processingTipIndex + 1) % processingTips.length;
            processingHintText.setText(processingTips[processingTipIndex]);
            processingHintText.postDelayed(this, 4500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        getWindow().setStatusBarColor(getColor(R.color.brand_surface));
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        draftStore = new MaterialDraftStore(this);
        importTaskManager = MaterialImportTaskManager.getInstance();
        resetTypeQuotas(20);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
        android.net.Uri externalUri = savedInstanceState == null
                ? ExternalImportActivity.findSharedUri(getIntent())
                : null;
        JSONObject saved = draftStore.load();
        if (importTaskManager.hasPendingTask()) {
            renderWelcome();
        } else if (externalUri != null) {
            renderWelcome();
            beginExternalDocumentFlow(externalUri);
        } else if (saved == null) {
            renderWelcome();
        } else {
            renderWelcome();
            new AlertDialog.Builder(this)
                    .setTitle("继续上次资料转题？")
                    .setMessage("24 小时内的章节计划和已通过校验的题目仍然保留。")
                    .setNegativeButton("删除草稿", (dialog, which) -> draftStore.clear())
                    .setPositiveButton("继续", (dialog, which) -> restoreDraft(saved))
                    .show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        importTaskManager.setListener(importTaskListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!new ApiConfigStore(this).getConfig().isComplete()) {
            return;
        }
        if (pendingDocumentUri != null) {
            android.net.Uri uri = pendingDocumentUri;
            pendingDocumentUri = null;
            confirmTokenUse("开始 AI 识别", () -> processDocument(uri));
        } else if (openPickerAfterAiSetup) {
            openPickerAfterAiSetup = false;
            confirmTokenUse("选择资料", this::launchDocumentPicker);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!suppressDraftSave && document != null && stage != Stage.WELCOME) {
            persistDraftQuietly();
        }
    }

    @Override
    protected void onStop() {
        importTaskManager.clearListener(importTaskListener);
        stopProcessingTips();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopProcessingTips();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void renderWelcome() {
        stage = Stage.WELCOME;
        beginScreen("资料转题", "把学习资料直接变成可刷题题库", view -> finish());

        MaterialCardView hero = card();
        LinearLayout heroBody = vertical(dp(18));
        TextView heroTitle = titleText("导入资料，AI 自动出题");
        heroBody.addView(heroTitle);
        TextView heroCopy = bodyText(
                "支持 TXT、文字版 PDF 和 DOCX。AI 会先判断是学习资料还是现成题库，再生成新题或忠实转换原题。"
        );
        heroBody.addView(heroCopy, topMargin(dp(8)));
        hero.addView(heroBody);
        content.addView(hero, bottomMargin(dp(14)));

        MaterialButton choose = primaryButton("选择资料");
        choose.setOnClickListener(view -> beginPickerFlow());
        content.addView(choose, matchWrap());

        TextView limits = bodyText("需要先配置 AI · 会消耗 Token · 单文件不超过 20 MB · 文字不超过 10 万字 · 扫描版 PDF 暂不支持");
        limits.setTextSize(13);
        content.addView(limits, topMargin(dp(10)));

        MaterialButton json = secondaryButton("导入已有 JSON / ZIP 题库");
        json.setOnClickListener(view -> startActivity(new Intent(this, ImportQuestionBankActivity.class)));
        content.addView(json, topMargin(dp(22)));
    }

    private void beginPickerFlow() {
        if (!new ApiConfigStore(this).getConfig().isComplete()) {
            openPickerAfterAiSetup = true;
            showAiSetupRequired();
            return;
        }
        confirmTokenUse("选择资料", this::launchDocumentPicker);
    }

    private void beginExternalDocumentFlow(android.net.Uri uri) {
        if (!new ApiConfigStore(this).getConfig().isComplete()) {
            pendingDocumentUri = uri;
            showAiSetupRequired();
            return;
        }
        confirmTokenUse("开始 AI 识别", () -> processDocument(uri));
    }

    private void showAiSetupRequired() {
        new AlertDialog.Builder(this)
                .setTitle("请先配置 AI")
                .setMessage("识别 TXT、PDF 或 DOCX 前，需要先填写 API 地址、API Key 和模型。配置完成后会自动继续。")
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    openPickerAfterAiSetup = false;
                    pendingDocumentUri = null;
                })
                .setPositiveButton("前往 AI 设置", (dialog, which) ->
                        startActivity(new Intent(this, SettingsActivity.class)))
                .show();
    }

    private void confirmTokenUse(String positiveLabel, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle("AI 识别会消耗 Token")
                .setMessage("应用会把本地提取的文字分段发送给你配置的 AI。学习资料会按知识点建议题量；现成题库会逐题转换为 JSON。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(positiveLabel, (dialog, which) -> action.run())
                .show();
    }

    private void launchDocumentPicker() {
        documentPicker.launch(new String[]{
                "text/plain",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        });
    }

    private void processDocument(android.net.Uri uri) {
        if (uri == null) {
            return;
        }
        ApiConfig config = new ApiConfigStore(this).getConfig();
        if (!config.isComplete()) {
            pendingDocumentUri = uri;
            showAiSetupRequired();
            return;
        }
        keepReadPermission(uri);
        importTaskManager.startDocumentAnalysis(this, uri);
        renderImportTask(importTaskManager.getSnapshot());
    }

    private void showDocumentAnalysisDecision(
            MaterialDocumentExtractor.DocumentData result,
            List<MaterialTextRules.Chapter> chapters,
            MaterialDocumentAnalysis analysis
    ) {
        int confidence = (int) Math.round(analysis.getConfidence() * 100d);
        String detected = analysis.isQuestionBank() ? "现成题库" : "学习资料";
        String detail = analysis.isQuestionBank()
                ? "接下来将识别并转换全部原题，完成后显示实际题数。"
                : "AI 将遍历全文知识点，再确定建议题量和题型。";
        Runnable learningAction = () -> startLearningPlanAnalysis(result, chapters);
        Runnable bankAction = () -> startQuestionBankConversion(result, chapters);
        new AlertDialog.Builder(this)
                .setTitle("AI 判断：" + detected)
                .setMessage(analysis.getReason() + "\n\n" + detail + "\n判断置信度：" + confidence + "%\n\n如果判断有误，可选择另一种处理方式。")
                .setCancelable(false)
                .setNegativeButton("重新选择", (dialog, which) -> renderWelcome())
                .setNeutralButton(analysis.isQuestionBank() ? "按资料出题" : "按题库转换",
                        (dialog, which) -> {
                            if (analysis.isQuestionBank()) {
                                learningAction.run();
                            } else {
                                bankAction.run();
                            }
                        })
                .setPositiveButton(analysis.isQuestionBank() ? "识别并转换" : "采用 AI 建议",
                        (dialog, which) -> {
                            if (analysis.isQuestionBank()) {
                                bankAction.run();
                            } else {
                                learningAction.run();
                            }
                        })
                .show();
    }

    private void startLearningPlanAnalysis(
            MaterialDocumentExtractor.DocumentData result,
            List<MaterialTextRules.Chapter> chapters
    ) {
        ApiConfig config = new ApiConfigStore(this).getConfig();
        if (!config.isComplete()) {
            showAiSetupRequired();
            return;
        }
        document = result;
        bankName = stripExtension(result.getDisplayName());
        convertedQuestionBank = false;
        importTaskManager.startLearningPlan(this, bankName, result, chapters);
        renderImportTask(importTaskManager.getSnapshot());
    }

    private void applyDocument(
            MaterialDocumentExtractor.DocumentData result,
            MaterialGenerationPlan recommended
    ) {
        convertedQuestionBank = false;
        document = result;
        bankName = stripExtension(result.getDisplayName());
        chapterChoices.clear();
        for (MaterialGenerationPlan.ChapterPlan chapter : recommended.getChapters()) {
            chapterChoices.add(new ChapterChoice(
                    chapter.getTitle(), chapter.getText(), chapter.getQuestionCount() > 0, chapter.getQuestionCount()
            ));
        }
        typeQuotas.clear();
        typeQuotas.putAll(recommended.getTypeQuotas());
        plan = recommended;
        questions = new JSONArray();
        unresolvedQuestions = new JSONArray();
        imageRolesConfirmed = false;
        generationResult = null;
        automaticAttemptsExhausted = false;
        renderPlan();
        persistDraftQuietly();
    }

    private void startQuestionBankConversion(
            MaterialDocumentExtractor.DocumentData result,
            List<MaterialTextRules.Chapter> chapters
    ) {
        ApiConfig config = new ApiConfigStore(this).getConfig();
        if (!config.isComplete()) {
            pendingDocumentUri = null;
            showAiSetupRequired();
            return;
        }
        convertedQuestionBank = true;
        document = result;
        bankName = stripExtension(result.getDisplayName());
        questions = new JSONArray();
        unresolvedQuestions = new JSONArray();
        imageRolesConfirmed = false;
        generationResult = null;
        automaticAttemptsExhausted = false;
        stage = Stage.WELCOME;
        importTaskManager.startQuestionBankConversion(
                this,
                bankName,
                result,
                chapters
        );
        renderImportTask(importTaskManager.getSnapshot());
    }

    private void applyQuestionBankConversion(
            List<MaterialTextRules.Chapter> chapters,
            MaterialQuestionGenerator.QuestionBankConversionResult converted
    ) {
        try {
            questions = copyJson(converted.getAccepted());
            unresolvedQuestions = copyJson(converted.getUnresolved());
            rebuildConvertedPlan(chapters);
            MaterialQuestionGenerator.PlanValidationResult checked =
                    MaterialQuestionGenerator.validatePlan(questions, plan);
            generationResult = new MaterialQuestionGenerator.GenerationResult(
                    converted.getReturnedCount(),
                    0,
                    checked,
                    converted.getDuplicateCount(),
                    converted.getInvalidCount()
            );
            stage = Stage.PREVIEW;
            persistDraftQuietly();
            renderPreview();
        } catch (Exception e) {
            renderWelcome();
            showError("无法整理识别结果", e);
        }
    }

    private void rebuildConvertedPlan(List<MaterialTextRules.Chapter> sourceChapters) throws Exception {
        plan = MaterialGenerationPlan.fromDetectedQuestions(
                bankName,
                document.getDisplayName(),
                sourceChapters,
                questions
        );
        chapterChoices.clear();
        for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
            chapterChoices.add(new ChapterChoice(
                    chapter.getTitle(), chapter.getText(), true, chapter.getQuestionCount()
            ));
        }
        typeQuotas.clear();
        typeQuotas.putAll(plan.getTypeQuotas());
    }

    private List<MaterialTextRules.Chapter> currentSourceChapters() {
        List<MaterialTextRules.Chapter> result = new ArrayList<>();
        for (ChapterChoice choice : chapterChoices) {
            result.add(new MaterialTextRules.Chapter(choice.title, choice.text));
        }
        return result;
    }

    private void applyConvertedEdits(JSONArray candidates) throws Exception {
        MaterialQuestionRules.ValidationResult detected =
                MaterialQuestionRules.validateDetected(candidates, bankName);
        if (detected.getAccepted().length() == 0) {
            throw new IllegalArgumentException("至少保留一道答案完整的题目");
        }
        int returned = generationResult == null
                ? candidates.length()
                : Math.max(generationResult.getReturnedCount(), candidates.length());
        int removedDuplicates = (generationResult == null ? 0 : generationResult.getRemovedDuplicateCount())
                + detected.getDuplicateCount();
        int removedInvalid = (generationResult == null ? 0 : generationResult.getRemovedInvalidCount())
                + detected.getInvalidCount();
        questions = copyJson(detected.getAccepted());
        imageRolesConfirmed = false;
        JSONArray stillUnresolved = new JSONArray();
        for (int i = 0; i < unresolvedQuestions.length(); i++) {
            JSONObject unresolved = unresolvedQuestions.optJSONObject(i);
            if (unresolved == null) continue;
            int originalNumber = unresolved.optInt("numberInPaper", 0);
            boolean resolved = false;
            for (int j = 0; j < questions.length(); j++) {
                JSONObject accepted = questions.optJSONObject(j);
                if (accepted != null && originalNumber > 0
                        && accepted.optInt("numberInPaper", 0) == originalNumber) {
                    resolved = true;
                    break;
                }
            }
            if (!resolved) stillUnresolved.put(unresolved);
        }
        unresolvedQuestions = stillUnresolved;
        rebuildConvertedPlan(currentSourceChapters());
        MaterialQuestionGenerator.PlanValidationResult checked =
                MaterialQuestionGenerator.validatePlan(questions, plan);
        generationResult = new MaterialQuestionGenerator.GenerationResult(
                returned, 0, checked, removedDuplicates, removedInvalid
        );
        persistDraftQuietly();
        renderPreview();
    }

    private void renderPlan() {
        stage = Stage.PLAN;
        beginScreen("资料转题工作台", "按章节规划题量与题型", view -> handleBack());
        addSteps(2);

        MaterialCardView fileCard = card();
        LinearLayout fileRow = horizontal(dp(14), Gravity.CENTER_VERTICAL);
        TextView kind = badge(document.getKind(), getColor(R.color.brand_primary));
        fileRow.addView(kind, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout fileText = vertical(0);
        fileText.addView(titleText(document.getDisplayName()));
        fileText.addView(bodyText(document.describe() + " · 识别到 " + chapterChoices.size() + " 个章节"));
        LinearLayout.LayoutParams fileTextParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fileTextParams.leftMargin = dp(12);
        fileRow.addView(fileText, fileTextParams);
        fileRow.addView(statusText("解析完成", true));
        fileCard.addView(fileRow);
        content.addView(fileCard, bottomMargin(dp(18)));

        addSectionTitle("题库名称");
        EditText nameEdit = editText(bankName, "输入题库名称");
        nameEdit.addTextChangedListener(simpleWatcher(value -> bankName = value.trim()));
        content.addView(nameEdit, bottomMargin(dp(18)));

        LinearLayout chapterHeader = horizontal(0, Gravity.CENTER_VERTICAL);
        TextView chapterTitle = sectionText("选择出题章节");
        chapterHeader.addView(chapterTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView all = actionText("全选");
        all.setOnClickListener(view -> {
            for (ChapterChoice choice : chapterChoices) {
                choice.selected = true;
                if (choice.questionCount <= 0) {
                    choice.questionCount = 1;
                }
            }
            renderPlan();
        });
        chapterHeader.addView(all);
        content.addView(chapterHeader, bottomMargin(dp(8)));

        MaterialCardView chapterCard = card();
        LinearLayout chapterList = vertical(0);
        for (int i = 0; i < chapterChoices.size(); i++) {
            ChapterChoice choice = chapterChoices.get(i);
            LinearLayout row = horizontal(dp(12), Gravity.CENTER_VERTICAL);
            CheckBox check = new CheckBox(this);
            check.setChecked(choice.selected);
            check.setOnCheckedChangeListener((button, checked) -> {
                choice.selected = checked;
                if (checked && choice.questionCount <= 0) {
                    choice.questionCount = 1;
                }
                updatePlanSummary();
            });
            row.addView(check);
            LinearLayout chapterCopy = vertical(0);
            chapterCopy.addView(titleText(choice.title));
            chapterCopy.addView(bodyText(NumberFormat.getIntegerInstance().format(choice.text.length()) + " 字"));
            row.addView(chapterCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            EditText count = numericEdit(choice.questionCount);
            count.setContentDescription(choice.title + "题量");
            count.addTextChangedListener(simpleWatcher(value -> {
                choice.questionCount = parseCount(value);
                updatePlanSummary();
            }));
            row.addView(count, new LinearLayout.LayoutParams(dp(64), dp(48)));
            chapterList.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            if (i < chapterChoices.size() - 1) {
                chapterList.addView(divider());
            }
        }
        chapterCard.addView(chapterList);
        content.addView(chapterCard, bottomMargin(dp(18)));

        MaterialCardView recommend = softCard();
        LinearLayout recommendation = vertical(dp(16));
        recommendation.addView(titleText("建议生成 " + sum(typeQuotas) + " 题"));
        recommendation.addView(bodyText("已按资料长度为各章节分配题量，可直接调整。"));
        TextView restore = actionText("恢复智能推荐");
        restore.setOnClickListener(view -> restoreRecommendation());
        recommendation.addView(restore, topMargin(dp(8)));
        recommend.addView(recommendation);
        content.addView(recommend, bottomMargin(dp(18)));

        addSectionTitle("题型计划");
        MaterialCardView typeCard = card();
        LinearLayout typeGrid = vertical(dp(8));
        addTypeEditor(typeGrid, MaterialQuestionRules.SINGLE, "单选题");
        addTypeEditor(typeGrid, MaterialQuestionRules.MULTI, "多选题");
        addTypeEditor(typeGrid, MaterialQuestionRules.JUDGE, "判断题");
        addTypeEditor(typeGrid, MaterialQuestionRules.FILL_BLANK, "填空题");
        addTypeEditor(typeGrid, MaterialQuestionRules.MATCHING, "连线题");
        typeCard.addView(typeGrid);
        content.addView(typeCard, bottomMargin(dp(12)));

        planTotalsText = bodyText("");
        planTotalsText.setTextSize(13);
        content.addView(planTotalsText);
        updatePlanSummary();

        MaterialButton saveDraft = secondaryButton("保存草稿");
        saveDraft.setOnClickListener(view -> {
            persistDraftQuietly();
            Toast.makeText(this, "草稿已保留 24 小时", Toast.LENGTH_SHORT).show();
        });
        footer.addView(saveDraft, weightedButton(1f, dp(6), 0));
        MaterialButton generate = primaryButton("按当前计划生成");
        generate.setOnClickListener(view -> startGeneration(false));
        footer.addView(generate, weightedButton(1.5f, dp(6), 0));
    }

    private void addTypeEditor(LinearLayout parent, String type, String label) {
        LinearLayout row = horizontal(dp(10), Gravity.CENTER_VERTICAL);
        TextView title = titleText(label);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        EditText value = numericEdit(typeQuotas.get(type));
        value.addTextChangedListener(simpleWatcher(text -> {
            typeQuotas.put(type, parseCount(text));
            updatePlanSummary();
        }));
        row.addView(value, new LinearLayout.LayoutParams(dp(72), dp(48)));
        parent.addView(row);
    }

    private void updatePlanSummary() {
        if (planTotalsText == null) {
            return;
        }
        int chapterTotal = 0;
        for (ChapterChoice choice : chapterChoices) {
            if (choice.selected) {
                chapterTotal += choice.questionCount;
            }
        }
        int typeTotal = sum(typeQuotas);
        StringBuilder text = new StringBuilder("章节题量 ")
                .append(chapterTotal)
                .append(" · 题型题量 ")
                .append(typeTotal);
        if (chapterTotal == typeTotal && typeTotal > 0) {
            try {
                MaterialGenerationPlan current = buildPlan();
                text.append(" · 预计首轮请求 ")
                        .append(MaterialQuestionGenerator.estimateInitialRequests(current))
                        .append(" 次");
            } catch (Exception e) {
                text.append(" · ").append(e.getMessage());
            }
        } else {
            text.append(" · 两项合计必须一致");
        }
        text.append("\n生成解析：开启 · 最多自动修复 3 轮");
        planTotalsText.setText(text.toString());
    }

    private void restoreRecommendation() {
        List<MaterialTextRules.Chapter> chapters = new ArrayList<>();
        for (ChapterChoice choice : chapterChoices) {
            chapters.add(new MaterialTextRules.Chapter(choice.title, choice.text));
        }
        MaterialGenerationPlan recommended = MaterialGenerationPlan.recommended(
                bankName, document.getDisplayName(), chapters
        );
        chapterChoices.clear();
        for (MaterialGenerationPlan.ChapterPlan chapter : recommended.getChapters()) {
            chapterChoices.add(new ChapterChoice(
                    chapter.getTitle(), chapter.getText(), chapter.getQuestionCount() > 0, chapter.getQuestionCount()
            ));
        }
        typeQuotas.clear();
        typeQuotas.putAll(recommended.getTypeQuotas());
        renderPlan();
    }

    private MaterialGenerationPlan buildPlan() {
        List<MaterialGenerationPlan.ChapterPlan> selected = new ArrayList<>();
        for (ChapterChoice choice : chapterChoices) {
            if (choice.selected && choice.questionCount > 0) {
                selected.add(new MaterialGenerationPlan.ChapterPlan(
                        choice.title, choice.text, choice.questionCount
                ));
            }
        }
        MaterialGenerationPlan result = new MaterialGenerationPlan(
                bankName, document.getDisplayName(), selected, typeQuotas
        );
        if (result.getSelectedCharacterCount() > MaterialTextRules.MAX_SELECTED_CHARACTERS) {
            throw new IllegalArgumentException("选中内容超过 10 万字，请减少章节或拆分生成多个题库");
        }
        return result;
    }

    private void startGeneration(boolean continueFromPreview) {
        try {
            plan = buildPlan();
        } catch (Exception e) {
            showError("无法开始生成", e);
            return;
        }
        ApiConfig config = new ApiConfigStore(this).getConfig();
        if (!config.isComplete()) {
            persistDraftQuietly();
            new AlertDialog.Builder(this)
                    .setTitle("请先配置 AI")
                    .setMessage("章节计划已保存。配置 API 地址、API Key 和模型后可继续。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("前往 AI 设置", (dialog, which) ->
                            startActivity(new Intent(this, SettingsActivity.class)))
                    .show();
            return;
        }
        JSONArray seed = continueFromPreview ? copyJson(questions) : new JSONArray();
        questions = copyJson(seed);
        if (!continueFromPreview) {
            generationResult = null;
        }
        automaticAttemptsExhausted = false;
        stage = Stage.GENERATING;
        lastSnapshot = MaterialQuestionGenerator.Snapshot.generating(
                plan.getTargetCount(), seed.length(), 0,
                MaterialQuestionGenerator.estimateInitialRequests(plan), "准备生成任务"
        );
        renderGenerating();
        persistDraftQuietly();
        executor.execute(() -> {
            try {
                MaterialQuestionGenerator generator = new MaterialQuestionGenerator();
                MaterialQuestionGenerator.GenerationResult result = generator.generate(
                        config,
                        plan,
                        seed,
                        snapshot -> runOnUiThread(() -> applySnapshot(snapshot))
                );
                runOnUiThread(() -> applyGenerationResult(result));
            } catch (Exception e) {
                runOnUiThread(() -> handleGenerationFailure(e));
            }
        });
    }

    private void applySnapshot(MaterialQuestionGenerator.Snapshot snapshot) {
        lastSnapshot = snapshot;
        if (snapshot.getCheckpoint() != null) {
            questions = copyJson(snapshot.getCheckpoint());
        }
        if (stage == Stage.GENERATING) {
            renderGenerating();
        }
        persistDraftQuietly();
    }

    private void applyGenerationResult(MaterialQuestionGenerator.GenerationResult result) {
        generationResult = result;
        questions = copyJson(result.getValidation().getAccepted());
        automaticAttemptsExhausted = !result.getValidation().isComplete()
                && result.getRepairRounds() >= MaterialQuestionGenerator.MAX_REPAIR_ROUNDS;
        stage = Stage.PREVIEW;
        persistDraftQuietly();
        renderPreview();
    }

    private void handleGenerationFailure(Exception error) {
        if (questions.length() > 0) {
            try {
                MaterialQuestionGenerator.PlanValidationResult validation =
                        MaterialQuestionGenerator.validatePlan(questions, plan);
                generationResult = new MaterialQuestionGenerator.GenerationResult(
                        questions.length(), 0, validation
                );
                questions = copyJson(validation.getAccepted());
                stage = Stage.PREVIEW;
                renderPreview();
            } catch (Exception ignored) {
                stage = Stage.PLAN;
                renderPlan();
            }
        } else {
            stage = Stage.PLAN;
            renderPlan();
        }
        showError("生成任务暂停", error);
        persistDraftQuietly();
    }

    private void renderGenerating() {
        beginScreen("生成任务", document.getDisplayName() + " · 可断点继续", view -> handleBack());
        addSteps(3);
        MaterialQuestionGenerator.Snapshot snapshot = lastSnapshot;
        LinearLayout metrics = horizontal(dp(8), Gravity.CENTER);
        metrics.addView(metric("目标", snapshot.target), weighted(1f));
        metrics.addView(metric("AI 返回", snapshot.returned), weighted(1f));
        metrics.addView(metric("有效", snapshot.valid), weighted(1f));
        metrics.addView(metric("重复", snapshot.duplicates), weighted(1f));
        metrics.addView(metric("待补", snapshot.missing), weighted(1f));
        content.addView(metrics, bottomMargin(dp(18)));

        addSectionTitle("校验与补齐");
        MaterialCardView pipeline = card();
        LinearLayout pipelineBody = vertical(dp(16));
        pipelineBody.addView(pipelineRow(snapshot.returned > 0, "分批生成", snapshot.message));
        pipelineBody.addView(pipelineRow(snapshot.valid > 0 || snapshot.duplicates > 0 || snapshot.invalid > 0,
                "首轮严格校验", snapshot.valid + " 题有效 · " + snapshot.invalid + " 题无效 · "
                        + snapshot.duplicates + " 题重复"));
        pipelineBody.addView(pipelineRow(snapshot.repairRound > 0,
                "定向补齐", snapshot.repairRound > 0 ? "正在进行第 " + snapshot.repairRound + " 轮" : "等待首轮结果"));
        pipelineBody.addView(pipelineRow(false, "完整题库二次校验", "重新核对章节、题型、答案与重复"));
        pipeline.addView(pipelineBody);
        content.addView(pipeline, bottomMargin(dp(14)));

        MaterialCardView warning = softCard();
        LinearLayout warningBody = vertical(dp(16));
        warningBody.addView(titleText("最多自动修复 3 轮"));
        warningBody.addView(bodyText(
                "资料不足时不会假装完成；会显示真实缺口，并提供继续尝试、返回调整或手动补题。"
        ));
        warning.addView(warningBody);
        content.addView(warning);

        ProgressBar progress = new ProgressBar(this);
        footer.addView(progress, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView running = bodyText("可以离开页面，已通过校验的进度会保留 24 小时");
        footer.addView(running, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void renderPreview() {
        stage = Stage.PREVIEW;
        MaterialQuestionGenerator.PlanValidationResult validation;
        try {
            validation = MaterialQuestionGenerator.validatePlan(questions, plan);
            questions = copyJson(validation.getAccepted());
        } catch (Exception e) {
            showError("无法校验题目", e);
            return;
        }
        int returned = generationResult == null ? questions.length() : generationResult.getReturnedCount();
        int rounds = generationResult == null ? 0 : generationResult.getRepairRounds();
        int removedDuplicates = generationResult == null
                ? validation.getDuplicateCount()
                : generationResult.getRemovedDuplicateCount();
        int removedInvalid = generationResult == null
                ? validation.getInvalidCount()
                : generationResult.getRemovedInvalidCount();
        generationResult = new MaterialQuestionGenerator.GenerationResult(
                returned, rounds, validation, removedDuplicates, removedInvalid
        );
        List<Integer> missingSourceNumbers = convertedQuestionBank
                ? SourceQuestionAudit.missingNumbers(document.getText(), questions)
                : new ArrayList<>();
        List<Integer> duplicateSourceNumbers = convertedQuestionBank
                ? SourceQuestionAudit.duplicateNumbers(questions) : new ArrayList<>();
        List<Integer> unexpectedSourceNumbers = convertedQuestionBank
                ? SourceQuestionAudit.unexpectedNumbers(document.getText(), questions)
                : new ArrayList<>();
        boolean hasDocxImages = "DOCX".equals(document.getKind())
                && document.getText().contains("【DOCX图片:");
        boolean complete = validation.isComplete() && missingSourceNumbers.isEmpty()
                && duplicateSourceNumbers.isEmpty() && unexpectedSourceNumbers.isEmpty()
                && unresolvedQuestions.length() == 0
                && (!hasDocxImages || imageRolesConfirmed);

        beginScreen("校对工作台", questions.length() + " 题 · 保存前最后检查",
                view -> {
                    if (convertedQuestionBank) {
                        renderWelcome();
                    } else {
                        renderPlan();
                    }
                });
        addSteps(4);
        MaterialCardView summary = complete ? successCard() : warningCard();
        LinearLayout summaryBody = vertical(dp(16));
        summaryBody.addView(titleText(complete
                ? (convertedQuestionBank ? "✓ AI 原题识别与格式校验完成" : "✓ 两轮严格校验通过")
                : hasDocxImages && !imageRolesConfirmed && validation.isComplete()
                        && missingSourceNumbers.isEmpty() && unresolvedQuestions.length() == 0
                        ? "待确认原卷图片角色"
                : convertedQuestionBank
                        ? "原卷题号需要校对"
                        : "还缺 " + validation.getMissingCount() + " 题"));
        summaryBody.addView(bodyText(
                questions.length() + " / " + plan.getTargetCount() + " 有效 · 移除重复 "
                        + generationResult.getRemovedDuplicateCount() + " 题 · 无效 "
                        + generationResult.getRemovedInvalidCount() + " 题"
        ));
        summary.addView(summaryBody);
        content.addView(summary, bottomMargin(dp(16)));
        if (hasDocxImages) {
            MaterialCardView imageNotice = warningCard();
            LinearLayout noticeBody = vertical(dp(14));
            noticeBody.addView(titleText("原卷含内嵌图片"));
            noticeBody.addView(bodyText("当前 AI 请求只包含图片位置标记，不包含图片像素。请逐题核对需看图的题目；"
                    + "无法确认的题目会列为待人工校对，不能凭图片文件名猜答案。"), topMargin(dp(6)));
            List<String> unassignedImages = unassignedDocxImages();
            if (!unassignedImages.isEmpty()) {
                noticeBody.addView(bodyText("尚未分配 " + unassignedImages.size() + " 张原卷图片："
                        + unassignedImages.subList(0, Math.min(8, unassignedImages.size()))
                        + (unassignedImages.size() > 8 ? " …" : "")), topMargin(dp(6)));
            }
            CheckBox confirmation = new CheckBox(this);
            confirmation.setText("我已逐题核对作答题图和提交后解析图");
            confirmation.setChecked(imageRolesConfirmed);
            confirmation.setOnCheckedChangeListener((button, checked) -> {
                imageRolesConfirmed = checked;
                persistDraftQuietly();
                renderPreview();
            });
            noticeBody.addView(confirmation, topMargin(dp(8)));
            imageNotice.addView(noticeBody);
            content.addView(imageNotice, bottomMargin(dp(14)));
        }

        if (!complete) {
            MaterialCardView gap = warningCard();
            LinearLayout gapBody = vertical(dp(14));
            gapBody.addView(titleText(convertedQuestionBank
                    ? !missingSourceNumbers.isEmpty() || !duplicateSourceNumbers.isEmpty()
                            || !unexpectedSourceNumbers.isEmpty() || unresolvedQuestions.length() > 0
                            ? "缺失 " + missingSourceNumbers + " · 重复 " + duplicateSourceNumbers
                                    + " · 非原卷题号 " + unexpectedSourceNumbers
                            : "请先确认图片显示时机"
                    : validation.isComplete() ? "请先确认图片显示时机"
                            : "缺口：" + missingText(validation.aggregateMissingByType())));
            gapBody.addView(bodyText(convertedQuestionBank
                    ? missingSourceNumbers.isEmpty() && duplicateSourceNumbers.isEmpty()
                            && unexpectedSourceNumbers.isEmpty() && unresolvedQuestions.length() == 0
                            ? "请检查图片角色后勾选确认。"
                            : "请按原文补录或修正原题号；现成题库不能降低题量掩盖漏题。"
                    : validation.isComplete() ? "请检查图片角色后勾选确认。"
                            : "保存按钮已锁定，必须补齐并重新通过完整校验。"));
            if (convertedQuestionBank) for (int i = 0; i < unresolvedQuestions.length(); i++) {
                JSONObject unresolved = unresolvedQuestions.optJSONObject(i);
                if (unresolved != null) gapBody.addView(bodyText("第 "
                        + unresolved.optInt("numberInPaper", 0) + " 题待人工校对："
                        + unresolved.optString("reason", "无法可靠转换")), topMargin(dp(6)));
            }
            LinearLayout choices = horizontal(dp(8), Gravity.END);
            if (!convertedQuestionBank && !validation.isComplete()) {
                MaterialButton adjust = compactButton("调整章节/题量");
                adjust.setOnClickListener(view -> renderPlan());
                choices.addView(adjust);
                MaterialButton lower = compactButton("降低到当前题量");
                lower.setOnClickListener(view -> adoptCurrentCounts());
                choices.addView(lower);
            } else if (!missingSourceNumbers.isEmpty() || unresolvedQuestions.length() > 0) {
                MaterialButton add = compactButton("补录原题");
                add.setOnClickListener(view -> showQuestionEditor(-1,
                        newManualQuestion(missingSourceNumbers.isEmpty() ? 0 : missingSourceNumbers.get(0))));
                choices.addView(add);
            }
            gapBody.addView(choices, topMargin(dp(10)));
            gap.addView(gapBody);
            content.addView(gap, bottomMargin(dp(14)));
        }

        for (MaterialGenerationPlan.ChapterPlan chapter : plan.getChapters()) {
            int chapterCount = 0;
            for (int i = 0; i < questions.length(); i++) {
                JSONObject item = questions.optJSONObject(i);
                if (item != null && chapter.getTitle().equals(item.optString("sourceChapter", ""))) {
                    chapterCount++;
                }
            }
            addSectionTitle(chapter.getTitle() + " · " + chapterCount + " 题");
            for (int i = 0; i < questions.length(); i++) {
                JSONObject item = questions.optJSONObject(i);
                if (item == null || !chapter.getTitle().equals(item.optString("sourceChapter", ""))) {
                    continue;
                }
                content.addView(questionCard(i, item), bottomMargin(dp(12)));
            }
        }

        if (!complete && automaticAttemptsExhausted && !convertedQuestionBank) {
            addManualFallback(validation);
        }

        MaterialButton draft = secondaryButton("保存草稿");
        draft.setOnClickListener(view -> {
            persistDraftQuietly();
            Toast.makeText(this, "草稿已保存", Toast.LENGTH_SHORT).show();
        });
        footer.addView(draft, weightedButton(1f, dp(6), 0));
        MaterialButton action = primaryButton(complete ? "保存为题库"
                : hasDocxImages && !imageRolesConfirmed && validation.isComplete()
                        && missingSourceNumbers.isEmpty() && unresolvedQuestions.length() == 0
                        ? "请先确认图片" : convertedQuestionBank ? "请先补齐原题" : "继续自动补齐");
        if (complete) {
            action.setOnClickListener(view -> saveAsQuestionBank());
        } else if (!convertedQuestionBank && !validation.isComplete()) {
            action.setOnClickListener(view -> startGeneration(true));
        } else {
            action.setEnabled(false);
        }
        footer.addView(action, weightedButton(1.5f, dp(6), 0));
    }

    private View questionCard(int index, JSONObject question) {
        MaterialCardView card = card();
        LinearLayout body = vertical(dp(16));
        LinearLayout header = horizontal(dp(8), Gravity.CENTER_VERTICAL);
        TextView type = statusText(typeLabel(question.optString("type", "")), false);
        header.addView(type);
        TextView quality = bodyText(question.optString("qualityState", "AI 生成"));
        quality.setTextSize(12);
        header.addView(quality, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(header);
        TextView stem = titleText((index + 1) + ". " + question.optString("stem", ""));
        stem.setTextSize(17);
        body.addView(stem, topMargin(dp(10)));
        if (MaterialQuestionRules.MATCHING.equals(question.optString("type"))) {
            body.addView(bodyText("左侧：\n" + matchingItemsForEdit(question, "leftItems")), topMargin(dp(10)));
            body.addView(bodyText("右侧：\n" + matchingItemsForEdit(question, "rightItems")), topMargin(dp(6)));
            body.addView(bodyText("标准连线：\n" + matchingPairsForEdit(question)), topMargin(dp(6)));
        } else {
            body.addView(bodyText("正确答案：" + answerText(question)), topMargin(dp(10)));
        }
        String explanation = question.optString("explanation", "").trim();
        if (!explanation.isEmpty() && !"null".equals(explanation)) {
            body.addView(bodyText("解析：" + explanation), topMargin(dp(6)));
        }
        String promptImage = question.isNull("image") ? "" : question.optString("image", "");
        JSONArray answerImages = question.optJSONArray("explanationImages");
        if (!promptImage.isEmpty() || (answerImages != null && answerImages.length() > 0)) {
            body.addView(bodyText("作答题图：" + (promptImage.isEmpty() ? "无" : promptImage)
                    + " · 提交后解析图：" + (answerImages == null ? 0 : answerImages.length()) + " 张"),
                    topMargin(dp(6)));
        }
        String snippet = question.optString("sourceSnippet", "").trim();
        if (!snippet.isEmpty()) {
            TextView source = bodyText("来源片段：" + snippet);
            source.setTextSize(13);
            source.setTextColor(getColor(R.color.brand_primary));
            body.addView(source, topMargin(dp(8)));
        }
        LinearLayout actions = horizontal(dp(8), Gravity.END);
        MaterialButton edit = compactButton("编辑");
        edit.setOnClickListener(view -> showQuestionEditor(index, question));
        actions.addView(edit);
        if (!convertedQuestionBank) {
            MaterialButton regenerate = compactButton("重新生成");
            regenerate.setOnClickListener(view -> regenerateQuestion(index, question));
            actions.addView(regenerate);
        }
        MaterialButton delete = compactButton("删除");
        delete.setTextColor(getColor(R.color.brand_error));
        delete.setOnClickListener(view -> deleteQuestion(index));
        actions.addView(delete);
        body.addView(actions, topMargin(dp(10)));
        card.addView(body);
        return card;
    }

    private void addManualFallback(MaterialQuestionGenerator.PlanValidationResult validation) {
        addSectionTitle("手动补题（不得已方法）");
        MaterialCardView fallback = warningCard();
        LinearLayout body = vertical(dp(16));
        body.addView(titleText("仍缺：" + missingText(validation.aggregateMissingByType())));
        body.addView(bodyText(
                "请把完整题目连同选项、正确答案和解析一起粘贴。支持一次粘贴一道或多道；解析为空可不填。"
        ), topMargin(dp(6)));
        EditText paste = editText("", "粘贴题干、选项、正确答案和解析");
        paste.setMinLines(7);
        paste.setGravity(Gravity.TOP | Gravity.START);
        body.addView(paste, topMargin(dp(12)));
        MaterialButton parse = primaryButton("解析并加入校对");
        parse.setOnClickListener(view -> parseManualQuestions(paste.getText().toString(), validation));
        body.addView(parse, topMargin(dp(10)));
        fallback.addView(body);
        content.addView(fallback, bottomMargin(dp(16)));
    }

    private void adoptCurrentCounts() {
        if (questions.length() == 0) {
            Toast.makeText(this, "当前没有可保存的有效题目", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, Integer> actualTypes = new LinkedHashMap<>();
        actualTypes.put(MaterialQuestionRules.SINGLE, 0);
        actualTypes.put(MaterialQuestionRules.MULTI, 0);
        actualTypes.put(MaterialQuestionRules.JUDGE, 0);
        actualTypes.put(MaterialQuestionRules.FILL_BLANK, 0);
        actualTypes.put(MaterialQuestionRules.MATCHING, 0);
        Map<String, Map<String, Integer>> actualMatrix = new LinkedHashMap<>();
        List<MaterialGenerationPlan.ChapterPlan> loweredChapters = new ArrayList<>();
        for (ChapterChoice choice : chapterChoices) {
            int count = 0;
            Map<String, Integer> row = new LinkedHashMap<>();
            row.put(MaterialQuestionRules.SINGLE, 0);
            row.put(MaterialQuestionRules.MULTI, 0);
            row.put(MaterialQuestionRules.JUDGE, 0);
            row.put(MaterialQuestionRules.FILL_BLANK, 0);
            row.put(MaterialQuestionRules.MATCHING, 0);
            for (int i = 0; i < questions.length(); i++) {
                JSONObject item = questions.optJSONObject(i);
                if (item != null && choice.title.equals(item.optString("sourceChapter", ""))) {
                    count++;
                    String itemType = MaterialQuestionRules.normalizeType(item.optString("type", ""));
                    if (itemType != null) {
                        row.put(itemType, row.get(itemType) + 1);
                    }
                }
            }
            choice.selected = count > 0;
            choice.questionCount = count;
            if (count > 0) {
                loweredChapters.add(new MaterialGenerationPlan.ChapterPlan(
                        choice.title, choice.text, count
                ));
                actualMatrix.put(choice.title, row);
            }
        }
        for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String type = MaterialQuestionRules.normalizeType(item.optString("type", ""));
            if (type != null) {
                actualTypes.put(type, actualTypes.get(type) + 1);
            }
        }
        typeQuotas.clear();
        typeQuotas.putAll(actualTypes);
        try {
            plan = new MaterialGenerationPlan(
                    bankName,
                    document.getDisplayName(),
                    loweredChapters,
                    actualTypes,
                    actualMatrix
            );
            MaterialQuestionGenerator.PlanValidationResult validation =
                    MaterialQuestionGenerator.validatePlan(questions, plan);
            generationResult = new MaterialQuestionGenerator.GenerationResult(
                    generationResult == null ? questions.length() : generationResult.getReturnedCount(),
                    generationResult == null ? 0 : generationResult.getRepairRounds(),
                    validation
            );
            automaticAttemptsExhausted = false;
            persistDraftQuietly();
            renderPreview();
        } catch (Exception e) {
            showError("无法降低题量", e);
        }
    }

    private void parseManualQuestions(
            String pasted,
            MaterialQuestionGenerator.PlanValidationResult validation
    ) {
        if (pasted.trim().isEmpty()) {
            Toast.makeText(this, "请先粘贴完整题目", Toast.LENGTH_SHORT).show();
            return;
        }
        ApiConfig config = new ApiConfigStore(this).getConfig();
        if (!config.isComplete()) {
            showError("无法解析", new IllegalArgumentException("请先配置 API 地址、API Key 和模型"));
            return;
        }
        Toast.makeText(this, "正在请 AI 识别全部粘贴题目，将消耗 Token…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                JSONArray parsed = new MaterialQuestionGenerator().parsePastedWithAi(
                        config, pasted, validation.aggregateMissingByType()
                );
                runOnUiThread(() -> addManualCandidates(parsed, validation));
            } catch (Exception e) {
                runOnUiThread(() -> showError("解析粘贴题目失败", e));
            }
        });
    }

    private void addManualCandidates(
            JSONArray parsed,
            MaterialQuestionGenerator.PlanValidationResult validation
    ) {
        try {
            Map<String, Map<String, Integer>> remaining = mutableMissing(validation.getMissingByChapter());
            JSONArray combined = copyJson(questions);
            int assigned = 0;
            for (int i = 0; i < parsed.length(); i++) {
                JSONObject item = parsed.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String type = MaterialQuestionRules.normalizeType(item.optString("type", ""));
                String chapter = firstMissingChapter(remaining, type);
                if (chapter == null) {
                    continue;
                }
                item.put("type", type);
                item.put("sourceChapter", chapter);
                item.put("paper", plan.getBankName());
                item.put("qualityState", "手动补题");
                combined.put(item);
                remaining.get(chapter).put(type, remaining.get(chapter).get(type) - 1);
                assigned++;
            }
            if (assigned == 0) {
                throw new IllegalArgumentException("没有识别到符合当前缺口的题目，请检查题型和正确答案");
            }
            MaterialQuestionGenerator.PlanValidationResult checked =
                    MaterialQuestionGenerator.validatePlan(combined, plan);
            if (checked.getAccepted().length() <= questions.length()) {
                throw new IllegalArgumentException("题目未通过答案、选项或重复检查，请编辑后重试");
            }
            questions = copyJson(checked.getAccepted());
            imageRolesConfirmed = false;
            generationResult = new MaterialQuestionGenerator.GenerationResult(
                    (generationResult == null ? 0 : generationResult.getReturnedCount()) + parsed.length(),
                    generationResult == null ? 0 : generationResult.getRepairRounds(),
                    checked
            );
            persistDraftQuietly();
            renderPreview();
        } catch (Exception e) {
            showError("无法加入手动题目", e);
        }
    }

    private void showQuestionEditor(int index, JSONObject existing) {
        LinearLayout form = vertical(dp(8));
        EditText originalNumber = editText(String.valueOf(existing.optInt("numberInPaper", index + 1)),
                "原卷题号");
        originalNumber.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        form.addView(originalNumber, matchWrap());
        Spinner type = new Spinner(this);
        String[] typeLabels = {"单选题", "多选题", "判断题", "填空题", "连线题"};
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, typeLabels));
        type.setSelection(typeIndex(existing.optString("type", "single")));
        form.addView(type, matchWrap());
        EditText stem = editText(existing.optString("stem", ""), "题干");
        stem.setMinLines(3);
        form.addView(stem, topMargin(dp(8)));
        EditText options = editText(optionsForEdit(existing), "选项，每行格式：A | 内容");
        options.setMinLines(4);
        form.addView(options, topMargin(dp(8)));
        EditText answers = editText(answerText(existing), "答案，例如 A,B；填空题填写纯答案");
        form.addView(answers, topMargin(dp(8)));
        LinearLayout matchingFields = vertical(dp(8));
        EditText leftItems = editText(matchingItemsForEdit(existing, "leftItems"), "左侧项目，每行：L1 | 内容");
        leftItems.setMinLines(3);
        matchingFields.addView(leftItems);
        EditText rightItems = editText(matchingItemsForEdit(existing, "rightItems"), "右侧项目，每行：R1 | 内容");
        rightItems.setMinLines(3);
        matchingFields.addView(rightItems);
        EditText pairs = editText(matchingPairsForEdit(existing), "标准连线，每行：L1 | R1");
        pairs.setMinLines(3);
        matchingFields.addView(pairs);
        Spinner matchingMode = new Spinner(this);
        matchingMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"左侧最多连接一次", "左侧可重复使用"}));
        JSONObject previousMatching = existing.optJSONObject("matching");
        matchingMode.setSelection(previousMatching != null
                && MatchingData.LEFT_REUSABLE.equals(previousMatching.optString("mode")) ? 1 : 0);
        matchingFields.addView(matchingMode);
        form.addView(matchingFields, topMargin(dp(8)));
        List<String> docxMedia = new ArrayList<>();
        if (document != null && "DOCX".equals(document.getKind())) {
            try {
                docxMedia.addAll(DocxMediaStore.list(this));
            } catch (Exception ignored) { }
        }
        Spinner questionImage = null;
        List<String> explanationImageRefs = new ArrayList<>();
        JSONArray existingExplanationImages = existing.optJSONArray("explanationImages");
        if (existingExplanationImages != null) for (int i = 0; i < existingExplanationImages.length(); i++) {
            explanationImageRefs.add(existingExplanationImages.optString(i));
        }
        if (!docxMedia.isEmpty()) {
            form.addView(bodyText("原卷图片角色：题图在作答前显示，解析图仅在提交后显示"),
                    topMargin(dp(12)));
            List<String> choices = new ArrayList<>();
            choices.add("无作答题图");
            choices.addAll(docxMedia);
            Spinner picker = new Spinner(this);
            picker.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, choices));
            int selectedImage = docxMedia.indexOf(existing.optString("image", ""));
            picker.setSelection(selectedImage < 0 ? 0 : selectedImage + 1);
            form.addView(picker, topMargin(dp(6)));
            questionImage = picker;
            ImageView preview = new ImageView(this);
            preview.setAdjustViewBounds(true);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setMaxHeight(dp(230));
            form.addView(preview, topMargin(dp(6)));
            picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position == 0) {
                        preview.setImageDrawable(null);
                        preview.setVisibility(View.GONE);
                    } else {
                        preview.setVisibility(View.VISIBLE);
                        showDocxPreview(preview, docxMedia.get(position - 1));
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            MaterialButton explanationPicker = compactButton("选择提交后解析图（"
                    + explanationImageRefs.size() + "）");
            explanationPicker.setOnClickListener(view -> {
                String[] labels = docxMedia.toArray(new String[0]);
                boolean[] checked = new boolean[labels.length];
                for (int i = 0; i < labels.length; i++) checked[i] = explanationImageRefs.contains(labels[i]);
                new AlertDialog.Builder(this)
                        .setTitle("选择解析图（仅提交后显示）")
                        .setMultiChoiceItems(labels, checked, (dialog, which, value) -> checked[which] = value)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("确定", (dialog, which) -> {
                            explanationImageRefs.clear();
                            for (int i = 0; i < labels.length; i++) {
                                if (checked[i]) explanationImageRefs.add(labels[i]);
                            }
                            explanationPicker.setText("选择提交后解析图（"
                                    + explanationImageRefs.size() + "）");
                        }).show();
            });
            form.addView(explanationPicker, topMargin(dp(6)));
        }
        Spinner finalQuestionImage = questionImage;
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean matching = position == 4;
                options.setVisibility(matching ? View.GONE : View.VISIBLE);
                answers.setVisibility(matching ? View.GONE : View.VISIBLE);
                matchingFields.setVisibility(matching ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        EditText explanation = editText(existing.optString("explanation", ""), "解析（可留空）");
        explanation.setMinLines(2);
        form.addView(explanation, topMargin(dp(8)));
        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(form);
        new AlertDialog.Builder(this)
                .setTitle("编辑题目")
                .setView(wrapper)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        JSONObject updated = buildEditedQuestion(
                                 existing, type.getSelectedItemPosition(),
                                 Integer.parseInt(originalNumber.getText().toString().trim()),
                                 stem.getText().toString(),
                                 options.getText().toString(), answers.getText().toString(),
                                 explanation.getText().toString(), leftItems.getText().toString(),
                                 rightItems.getText().toString(), pairs.getText().toString(),
                                 matchingMode.getSelectedItemPosition(),
                                 finalQuestionImage == null ? (existing.isNull("image")
                                         ? null : existing.optString("image", ""))
                                         : finalQuestionImage.getSelectedItemPosition() == 0
                                                 ? null : docxMedia.get(finalQuestionImage.getSelectedItemPosition() - 1),
                                 explanationImageRefs
                        );
                        replaceQuestion(index, updated);
                    } catch (Exception e) {
                        showError("无法保存题目", e);
                    }
                })
                .show();
    }

    private JSONObject buildEditedQuestion(
            JSONObject existing,
            int typeIndex,
            int numberInPaper,
            String stem,
            String optionsText,
            String answersText,
            String explanation,
            String leftText,
            String rightText,
            String pairsText,
            int matchingModeIndex,
            String imageReference,
            List<String> explanationImageRefs
    ) throws Exception {
        String[] types = {
                MaterialQuestionRules.SINGLE,
                MaterialQuestionRules.MULTI,
                MaterialQuestionRules.JUDGE,
                MaterialQuestionRules.FILL_BLANK,
                MaterialQuestionRules.MATCHING
        };
        String selectedType = types[Math.max(0, Math.min(types.length - 1, typeIndex))];
        if (numberInPaper <= 0) throw new IllegalArgumentException("原卷题号必须大于 0");
        if (stem.trim().isEmpty()) throw new IllegalArgumentException("题干不能为空");
        if (MaterialQuestionRules.MATCHING.equals(selectedType)) {
            MatchingData matching = new MatchingData(
                    matchingModeIndex == 1 ? MatchingData.LEFT_REUSABLE : MatchingData.ONE_TO_ONE,
                    parseMatchingItems(leftText), parseMatchingItems(rightText), parseMatchingPairs(pairsText));
            JSONObject result = new JSONObject(existing.toString());
            result.put("type", selectedType).put("stem", stem.trim())
                    .put("numberInPaper", numberInPaper)
                    .put("options", new JSONArray()).put("answers", new JSONArray())
                    .put("matching", matching.toJson())
                    .put("explanation", explanation.trim().isEmpty() ? JSONObject.NULL : explanation.trim())
                    .put("qualityState", "人工编辑");
            result.put("image", imageReference == null ? JSONObject.NULL : imageReference);
            result.put("explanationImages", new JSONArray(explanationImageRefs));
            return result;
        }
        JSONArray optionArray = new JSONArray();
        if (!MaterialQuestionRules.FILL_BLANK.equals(selectedType)) {
            for (String line : optionsText.split("\n")) {
                String value = line.trim();
                if (value.isEmpty()) {
                    continue;
                }
                String[] parts = value.split("\\s*[|｜]\\s*|\\s*[.、．:]\\s*", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("选项格式应为：A | 内容");
                }
                optionArray.put(new JSONObject()
                        .put("key", parts[0].trim().toUpperCase(Locale.ROOT))
                        .put("text", parts[1].trim()));
            }
        }
        JSONArray answerArray = new JSONArray();
        if (MaterialQuestionRules.FILL_BLANK.equals(selectedType)) {
            String value = answersText.trim().replaceFirst("^[（(]\\s*\\d+\\s*[)）]\\s*", "");
            if (!value.isEmpty()) {
                answerArray.put(value);
            }
        } else {
            for (String answer : answersText.toUpperCase(Locale.ROOT).split("[,，、\\s]+")) {
                if (!answer.trim().isEmpty()) {
                    answerArray.put(answer.trim());
                }
            }
        }
        JSONObject result = new JSONObject(existing.toString());
        result.put("type", selectedType);
        result.put("numberInPaper", numberInPaper);
        result.put("stem", stem.trim());
        result.put("options", optionArray);
        result.put("answers", answerArray);
        result.remove("matching");
        result.put("explanation", explanation.trim().isEmpty() ? JSONObject.NULL : explanation.trim());
        result.put("qualityState", "人工编辑");
        result.put("image", imageReference == null ? JSONObject.NULL : imageReference);
        result.put("explanationImages", new JSONArray(explanationImageRefs));
        return result;
    }

    private void replaceQuestion(int index, JSONObject updated) throws Exception {
        JSONArray combined = new JSONArray();
        for (int i = 0; i < questions.length(); i++) {
            combined.put(i == index ? updated : questions.getJSONObject(i));
        }
        if (index < 0) combined.put(updated);
        if (convertedQuestionBank) {
            applyConvertedEdits(combined);
            return;
        }
        MaterialQuestionGenerator.PlanValidationResult checked =
                MaterialQuestionGenerator.validatePlan(combined, plan);
        questions = copyJson(checked.getAccepted());
        imageRolesConfirmed = false;
        generationResult = new MaterialQuestionGenerator.GenerationResult(
                generationResult == null ? combined.length() : generationResult.getReturnedCount(),
                generationResult == null ? 0 : generationResult.getRepairRounds(),
                checked
        );
        persistDraftQuietly();
        renderPreview();
    }

    private JSONObject newManualQuestion(int numberInPaper) {
        JSONObject item = new JSONObject();
        try {
            item.put("id", "manual_" + System.currentTimeMillis());
            item.put("index", questions.length() + 1);
            item.put("numberInPaper", numberInPaper);
            item.put("paper", bankName);
            item.put("sourceChapter", chapterChoices.isEmpty()
                    ? "原卷" : chapterChoices.get(0).title);
            item.put("type", MaterialQuestionRules.SINGLE);
            item.put("stem", "");
            item.put("options", new JSONArray());
            item.put("answers", new JSONArray());
            item.put("explanation", JSONObject.NULL);
        } catch (Exception ignored) { }
        return item;
    }

    private void deleteQuestion(int index) {
        new AlertDialog.Builder(this)
                .setTitle("删除这道题？")
                .setMessage(convertedQuestionBank
                        ? "这道原题会从转换结果中移除。"
                        : "删除后会产生题型缺口，需要重新补齐才能保存题库。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除", (dialog, which) -> {
                    try {
                        JSONArray remaining = new JSONArray();
                        for (int i = 0; i < questions.length(); i++) {
                            if (i != index) {
                                remaining.put(questions.getJSONObject(i));
                            }
                        }
                        if (convertedQuestionBank) {
                            applyConvertedEdits(remaining);
                            return;
                        }
                        MaterialQuestionGenerator.PlanValidationResult checked =
                                MaterialQuestionGenerator.validatePlan(remaining, plan);
                        questions = copyJson(checked.getAccepted());
                        imageRolesConfirmed = false;
                        generationResult = new MaterialQuestionGenerator.GenerationResult(
                                generationResult == null ? remaining.length() : generationResult.getReturnedCount(),
                                generationResult == null ? 0 : generationResult.getRepairRounds(), checked
                        );
                        persistDraftQuietly();
                        renderPreview();
                    } catch (Exception e) {
                        showError("删除失败", e);
                    }
                })
                .show();
    }

    private void regenerateQuestion(int index, JSONObject oldQuestion) {
        ApiConfig config = new ApiConfigStore(this).getConfig();
        if (!config.isComplete()) {
            showError("无法重新生成", new IllegalArgumentException("请先配置 AI"));
            return;
        }
        Toast.makeText(this, "正在重新生成这道题…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                JSONObject replacement = new MaterialQuestionGenerator().regenerateOne(
                        config,
                        plan,
                        oldQuestion.optString("sourceChapter", ""),
                        oldQuestion.optString("type", ""),
                        oldQuestion.optString("stem", "")
                );
                runOnUiThread(() -> {
                    try {
                        replaceQuestion(index, replacement);
                    } catch (Exception e) {
                        showError("重新生成的题目未通过校验", e);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("重新生成失败", e));
            }
        });
    }

    private void saveAsQuestionBank() {
        try {
            MaterialQuestionGenerator.PlanValidationResult validation =
                    MaterialQuestionGenerator.validatePlan(questions, plan);
            if (!validation.isComplete()) {
                throw new IllegalStateException("有效题量尚未达到目标，不能保存");
            }
            if ("DOCX".equals(document.getKind())
                    && document.getText().contains("【DOCX图片:") && !imageRolesConfirmed) {
                throw new IllegalStateException("请先逐题核对并确认 DOCX 图片角色");
            }
            if (convertedQuestionBank) {
                if (unresolvedQuestions.length() > 0) {
                    throw new IllegalStateException("仍有 " + unresolvedQuestions.length()
                            + " 道原题待人工校对，不能保存");
                }
                List<Integer> missing = SourceQuestionAudit.missingNumbers(
                        document.getText(), validation.getAccepted());
                List<Integer> duplicate = SourceQuestionAudit.duplicateNumbers(validation.getAccepted());
                List<Integer> unexpected = SourceQuestionAudit.unexpectedNumbers(
                        document.getText(), validation.getAccepted());
                if (!missing.isEmpty() || !duplicate.isEmpty() || !unexpected.isEmpty()) {
                    throw new IllegalStateException("原卷题号未齐或重复，不能保存：缺失 "
                            + missing + "，重复 " + duplicate + "，非原卷 " + unexpected);
                }
            }
            JSONArray finalQuestions = new JSONArray(validation.getAccepted().toString());
            if (convertedQuestionBank && "DOCX".equals(document.getKind())) {
                List<String> availableImages = DocxMediaStore.list(this);
                if (document.getText().contains("【DOCX图片:") && availableImages.isEmpty()) {
                    throw new IllegalStateException("原 DOCX 图片草稿已失效，请重新选择文件");
                }
                for (int i = 0; i < finalQuestions.length(); i++) {
                    JSONObject item = finalQuestions.getJSONObject(i);
                    Object image = item.opt("image");
                    if (image instanceof String && !((String) image).trim().isEmpty()
                            && !availableImages.contains(image)) {
                        throw new IllegalStateException("第 " + (i + 1) + " 题的题图不在原 DOCX 中");
                    }
                    JSONArray explanationImages = item.optJSONArray("explanationImages");
                    if (explanationImages == null) continue;
                    for (int j = 0; j < explanationImages.length(); j++) {
                        if (!availableImages.contains(explanationImages.getString(j))) {
                            throw new IllegalStateException("第 " + (i + 1) + " 题的解析图不在原 DOCX 中");
                        }
                    }
                }
            }
            for (int i = 0; i < finalQuestions.length(); i++) {
                JSONObject item = finalQuestions.getJSONObject(i);
                item.remove("sourceSnippet");
                item.remove("qualityState");
                item.put("sourceName", document.getDisplayName());
            }
            QuestionRepository.QuestionBank created = QuestionRepository.importQuestionBank(
                    this, plan.getBankName(), finalQuestions
            );
            try {
                materializeDocxImages(created, finalQuestions);
            } catch (Exception imageError) {
                QuestionRepository.deleteQuestionBank(this, created.getId());
                throw imageError;
            }
            getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(MainActivity.KEY_SELECTED_BANK, created.getId())
                    .apply();
            suppressDraftSave = true;
            draftStore.clear();
            Intent result = new Intent();
            result.putExtra(EXTRA_CREATED_BANK_ID, created.getId());
            setResult(RESULT_OK, result);
            Toast.makeText(this,
                    (convertedQuestionBank ? "已转换题库：" : "已生成题库：") + created.getDisplayName(),
                    Toast.LENGTH_LONG
            ).show();
            finish();
        } catch (Exception e) {
            showError("保存题库失败", e);
        }
    }

    private void materializeDocxImages(QuestionRepository.QuestionBank bank,
                                       JSONArray finalQuestions) throws Exception {
        Map<String, String> copied = new LinkedHashMap<>();
        boolean hasDocxImages = false;
        for (int i = 0; i < finalQuestions.length(); i++) {
            JSONObject item = finalQuestions.getJSONObject(i);
            Object image = item.opt("image");
            if (image instanceof String && DocxMediaStore.isReference((String) image)) {
                item.put("image", importDocxImage(bank.getId(), (String) image, copied));
                hasDocxImages = true;
            }
            JSONArray explanations = item.optJSONArray("explanationImages");
            if (explanations == null) continue;
            JSONArray remapped = new JSONArray();
            for (int j = 0; j < explanations.length(); j++) {
                String reference = explanations.getString(j);
                if (DocxMediaStore.isReference(reference)) {
                    remapped.put(importDocxImage(bank.getId(), reference, copied));
                    hasDocxImages = true;
                } else remapped.put(reference);
            }
            item.put("explanationImages", remapped);
        }
        if (hasDocxImages) {
            QuestionRepository.saveImportedQuestionBank(this, bank.getId(), bank.getDisplayName(),
                    QuestionRepository.parsePackagedQuestions(finalQuestions, bank.getDisplayName()));
        }
    }

    private String importDocxImage(String bankId, String reference,
                                   Map<String, String> copied) throws Exception {
        String local = copied.get(reference);
        if (local == null) {
            local = DocxMediaStore.importToBank(this, bankId, reference);
            copied.put(reference, local);
        }
        return local;
    }

    private void handleImportTaskSnapshot(MaterialImportTaskManager.Snapshot snapshot) {
        if (snapshot == null || isFinishing() || isDestroyed()) {
            return;
        }
        if (snapshot.getStatus() == MaterialImportTaskManager.Status.RUNNING) {
            renderImportTask(snapshot);
            return;
        }
        if (snapshot.getStatus() == MaterialImportTaskManager.Status.FAILED) {
            renderImportTaskFailure(snapshot);
            return;
        }
        if (snapshot.getStatus() != MaterialImportTaskManager.Status.SUCCEEDED) {
            return;
        }
        MaterialImportTaskManager.TaskResult result =
                importTaskManager.consumeResult(snapshot.getTaskId());
        if (result == null) {
            return;
        }
        if (snapshot.getKind() == MaterialImportTaskManager.TaskKind.ANALYZE_DOCUMENT) {
            showDocumentAnalysisDecision(
                    result.getDocument(),
                    result.getChapters(),
                    result.getAnalysis()
            );
        } else if (snapshot.getKind() == MaterialImportTaskManager.TaskKind.LEARNING_PLAN) {
            applyDocument(result.getDocument(), result.getPlan());
        } else if (snapshot.getKind()
                == MaterialImportTaskManager.TaskKind.QUESTION_BANK_CONVERSION) {
            convertedQuestionBank = true;
            document = result.getDocument();
            bankName = stripExtension(document.getDisplayName());
            applyQuestionBankConversion(result.getChapters(), result.getConversion());
        }
    }

    private void renderImportTask(MaterialImportTaskManager.Snapshot snapshot) {
        boolean needsLayout = stage != Stage.PROCESSING
                || renderedImportTaskId != snapshot.getTaskId()
                || processingProgress == null;
        if (needsLayout) {
            beginScreen(snapshot.getTitle(), "可以返回首页，任务会在后台静默继续", view -> finish());
            stage = Stage.PROCESSING;
            renderedImportTaskId = snapshot.getTaskId();

            MaterialCardView taskCard = softCard();
            LinearLayout taskBody = vertical(dp(18));
            taskBody.addView(statusText("后台处理中", false), bottomMargin(dp(12)));
            taskBody.addView(sectionText(taskHeadline(snapshot.getKind())));
            processingMessageText = bodyText(snapshot.getMessage());
            taskBody.addView(processingMessageText, topMargin(dp(8)));

            processingProgress = new ProgressBar(
                    this,
                    null,
                    android.R.attr.progressBarStyleHorizontal
            );
            processingProgress.setIndeterminate(false);
            processingProgress.setProgressTintList(
                    ColorStateList.valueOf(getColor(R.color.brand_primary))
            );
            processingProgress.setProgressBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.brand_border))
            );
            taskBody.addView(processingProgress, topMargin(dp(18)));
            processingProgressText = bodyText("");
            processingProgressText.setTextSize(12);
            taskBody.addView(processingProgressText, topMargin(dp(6)));
            taskCard.addView(taskBody);
            content.addView(taskCard, bottomMargin(dp(14)));

            MaterialCardView stepsCard = card();
            LinearLayout stepsBody = vertical(dp(18));
            stepsBody.addView(titleText("当前处理路径"), bottomMargin(dp(12)));
            for (int i = 0; i < processingStepTexts.length; i++) {
                processingStepTexts[i] = bodyText("");
                processingStepTexts[i].setTextSize(14);
                stepsBody.addView(processingStepTexts[i], i == 0 ? matchWrap() : topMargin(dp(12)));
            }
            stepsCard.addView(stepsBody);
            content.addView(stepsCard, bottomMargin(dp(14)));

            MaterialCardView hintCard = card();
            LinearLayout hintBody = vertical(dp(16));
            TextView hintLabel = titleText("等待期间");
            hintLabel.setTextSize(14);
            hintBody.addView(hintLabel);
            processingHintText = bodyText("");
            hintBody.addView(processingHintText, topMargin(dp(7)));
            hintCard.addView(hintBody);
            content.addView(hintCard);

            MaterialButton leave = primaryButton("返回首页 · 后台继续");
            leave.setOnClickListener(view -> finish());
            footer.addView(leave, matchWrap());
        }
        updateImportTaskViews(snapshot);
        startProcessingTips(snapshot.getKind());
    }

    private void updateImportTaskViews(MaterialImportTaskManager.Snapshot snapshot) {
        int total = Math.max(1, snapshot.getTotal());
        int completed = Math.max(0, Math.min(total, snapshot.getCompleted()));
        processingMessageText.setText(snapshot.getMessage());
        processingProgress.setMax(total);
        processingProgress.setProgress(completed, true);
        processingProgressText.setText(
                completed + " / " + total
                        + (snapshot.getKind() == MaterialImportTaskManager.TaskKind.ANALYZE_DOCUMENT
                        ? " 个步骤" : " 次 AI 请求")
        );

        if (snapshot.getKind() == MaterialImportTaskManager.TaskKind.ANALYZE_DOCUMENT) {
            setProcessingStep(0, completed >= 1, completed == 0,
                    "读取文件与提取正文");
            setProcessingStep(1, completed >= 2, completed == 1,
                    "整理章节并检查资料大小");
            setProcessingStep(2, completed >= 3, completed >= 2,
                    "AI 判断资料类型与处理方式");
        } else {
            setProcessingStep(0, true, false, "原资料已安全读取");
            setProcessingStep(1, completed >= total, completed < total,
                    snapshot.getKind() == MaterialImportTaskManager.TaskKind.LEARNING_PLAN
                            ? "AI 分段盘点知识点"
                            : "AI 分段识别并转换原题");
            setProcessingStep(2, false, completed >= total,
                    "整理结果并执行格式校验");
        }
    }

    private void setProcessingStep(int index, boolean complete, boolean active, String label) {
        TextView view = processingStepTexts[index];
        String prefix = complete ? "✓  " : active ? "●  " : "○  ";
        view.setText(prefix + label);
        view.setTextColor(getColor(complete
                ? R.color.brand_success
                : active ? R.color.brand_primary : R.color.brand_text_muted));
    }

    private String taskHeadline(MaterialImportTaskManager.TaskKind kind) {
        if (kind == MaterialImportTaskManager.TaskKind.ANALYZE_DOCUMENT) {
            return "先读懂资料，再决定怎么处理";
        }
        if (kind == MaterialImportTaskManager.TaskKind.LEARNING_PLAN) {
            return "正在从全文提炼可考查知识点";
        }
        return "正在保留原题并转换为标准题库";
    }

    private void startProcessingTips(MaterialImportTaskManager.TaskKind kind) {
        if (processingHintText == null) {
            return;
        }
        stopProcessingTips();
        if (kind == MaterialImportTaskManager.TaskKind.ANALYZE_DOCUMENT) {
            processingTips = new String[]{
                    "AI 会先判断这是学习资料还是现成题库，不再用脚本猜题型。",
                    "这里只显示真实处理步骤，不使用虚假百分比。",
                    "你可以先返回首页；重新进入资料转题时会自动接回进度。"
            };
        } else {
            processingTips = new String[]{
                    "长资料会被分段处理，因此可能需要多次 AI 请求。",
                    "你可以先返回首页；离开本页不会中断当前任务。",
                    "完成后会校验题型、答案和重复题，再进入预览。",
                    "如果请求失败，页面会区分网络、鉴权、额度和返回格式问题。"
            };
        }
        processingTipIndex = 0;
        processingHintText.setText(processingTips[0]);
        processingHintText.postDelayed(rotateProcessingTip, 4500L);
    }

    private void stopProcessingTips() {
        if (processingHintText != null) {
            processingHintText.removeCallbacks(rotateProcessingTip);
        }
    }

    private void renderImportTaskFailure(MaterialImportTaskManager.Snapshot snapshot) {
        AiFailureInfo failure = snapshot.getFailure() == null
                ? AiFailureInfo.from(null)
                : snapshot.getFailure();
        beginScreen("处理未完成", failure.getTitle(), view -> finish());
        stage = Stage.TASK_ERROR;
        renderedImportTaskId = snapshot.getTaskId();

        MaterialCardView errorCard = card();
        errorCard.setCardBackgroundColor(Color.rgb(254, 242, 242));
        errorCard.setStrokeColor(getColor(R.color.brand_error));
        LinearLayout errorBody = vertical(dp(18));
        TextView label = statusText("已暂停", false);
        label.setTextColor(getColor(R.color.brand_error));
        errorBody.addView(label, bottomMargin(dp(12)));
        errorBody.addView(sectionText(failure.getTitle()));
        errorBody.addView(bodyText(failure.getMessage()), topMargin(dp(9)));
        TextView recovery = bodyText(failure.getRecovery());
        recovery.setTextColor(getColor(R.color.brand_ink));
        errorBody.addView(recovery, topMargin(dp(14)));
        errorCard.addView(errorBody);
        content.addView(errorCard, bottomMargin(dp(14)));

        MaterialCardView progressCard = card();
        LinearLayout progressBody = vertical(dp(16));
        progressBody.addView(titleText("已保留任务信息"));
        progressBody.addView(bodyText(
                "中断前已完成 " + snapshot.getCompleted() + " / " + snapshot.getTotal()
                        + "。点击重试会重新执行当前识别阶段。"
        ), topMargin(dp(7)));
        progressCard.addView(progressBody);
        content.addView(progressCard);

        if (failure.shouldOfferSettings()) {
            MaterialButton settings = secondaryButton("检查 AI 设置");
            settings.setOnClickListener(view ->
                    startActivity(new Intent(this, SettingsActivity.class)));
            content.addView(settings, topMargin(dp(14)));
        }
        MaterialButton chooseAgain = secondaryButton("放弃任务并重新选择资料");
        chooseAgain.setOnClickListener(view -> {
            importTaskManager.dismissFinishedTask();
            renderWelcome();
            beginPickerFlow();
        });
        content.addView(chooseAgain, topMargin(dp(10)));

        MaterialButton retry = primaryButton("重试当前任务");
        retry.setOnClickListener(view -> importTaskManager.retry());
        footer.addView(retry, matchWrap());
    }

    private void beginScreen(String title, String subtitle, View.OnClickListener backAction) {
        stopProcessingTips();
        renderedImportTaskId = -1L;
        processingMessageText = null;
        processingProgressText = null;
        processingHintText = null;
        processingProgress = null;
        LinearLayout root = vertical(0);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));
        root.setBackgroundColor(getColor(R.color.brand_surface));
        setContentView(root);

        LinearLayout header = horizontal(dp(12), Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back_curved);
        back.setBackgroundResource(R.drawable.bg_back_button);
        back.setContentDescription("返回");
        back.setPadding(dp(11), dp(11), dp(11), dp(11));
        back.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        back.setOnClickListener(backAction);
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout copy = vertical(0);
        TextView heading = titleText(title);
        heading.setTextSize(25);
        copy.addView(heading);
        TextView sub = bodyText(subtitle);
        sub.setTextSize(13);
        copy.addView(sub);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.leftMargin = dp(8);
        header.addView(copy, copyParams);
        root.addView(header, bottomMargin(dp(14)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = vertical(0);
        content.setPadding(0, dp(4), 0, dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
        footer = horizontal(dp(8), Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(10), 0, 0);
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addSteps(int active) {
        LinearLayout steps = horizontal(dp(6), Gravity.CENTER);
        for (int i = 1; i <= 4; i++) {
            View bar = new View(this);
            bar.setBackgroundColor(i <= active
                    ? getColor(R.color.brand_primary)
                    : getColor(R.color.brand_border));
            steps.addView(bar, new LinearLayout.LayoutParams(0, dp(4), 1f));
        }
        content.addView(steps, bottomMargin(dp(18)));
    }

    private void handleBack() {
        if (stage == Stage.PROCESSING || stage == Stage.TASK_ERROR) {
            finish();
            return;
        }
        if (stage == Stage.GENERATING) {
            persistDraftQuietly();
            new AlertDialog.Builder(this)
                    .setTitle("离开生成任务？")
                    .setMessage("已通过校验的进度会保留 24 小时，下次可继续补齐。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("离开", (dialog, which) -> finish())
                    .show();
            return;
        }
        if (stage == Stage.PREVIEW) {
            if (convertedQuestionBank) {
                renderWelcome();
            } else {
                renderPlan();
            }
            return;
        }
        if (stage == Stage.PLAN) {
            persistDraftQuietly();
        }
        finish();
    }

    private void keepReadPermission(android.net.Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Some providers only grant temporary access; the active task can still read it.
        }
    }

    private void persistDraftQuietly() {
        if (document == null || suppressDraftSave) {
            return;
        }
        try {
            draftStore.save(buildDraftJson());
        } catch (Exception ignored) {
        }
    }

    private JSONObject buildDraftJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("stage", stage.name());
        root.put("bankName", bankName);
        root.put("sourceName", document.getDisplayName());
        root.put("kind", document.getKind());
        root.put("fileSize", document.getFileSize());
        root.put("pageCount", document.getPageCount());
        root.put("text", document.getText());
        JSONArray chapters = new JSONArray();
        for (ChapterChoice choice : chapterChoices) {
            chapters.put(new JSONObject()
                    .put("title", choice.title)
                    .put("text", choice.text)
                    .put("selected", choice.selected)
                    .put("questionCount", choice.questionCount));
        }
        root.put("chapters", chapters);
        JSONObject types = new JSONObject();
        for (Map.Entry<String, Integer> entry : typeQuotas.entrySet()) {
            types.put(entry.getKey(), entry.getValue());
        }
        root.put("typeQuotas", types);
        if (plan != null && (stage == Stage.PREVIEW || stage == Stage.GENERATING)) {
            JSONObject matrixJson = new JSONObject();
            for (Map.Entry<String, Map<String, Integer>> row : plan.allocateTypeMatrix().entrySet()) {
                JSONObject rowJson = new JSONObject();
                for (Map.Entry<String, Integer> value : row.getValue().entrySet()) {
                    rowJson.put(value.getKey(), value.getValue());
                }
                matrixJson.put(row.getKey(), rowJson);
            }
            root.put("typeMatrix", matrixJson);
        }
        root.put("questions", questions);
        root.put("unresolvedQuestions", unresolvedQuestions);
        root.put("imageRolesConfirmed", imageRolesConfirmed);
        root.put("convertedQuestionBank", convertedQuestionBank);
        root.put("automaticAttemptsExhausted", automaticAttemptsExhausted);
        root.put("returnedCount", generationResult == null ? questions.length() : generationResult.getReturnedCount());
        root.put("repairRounds", generationResult == null ? 0 : generationResult.getRepairRounds());
        return root;
    }

    private void restoreDraft(JSONObject saved) {
        try {
            document = new MaterialDocumentExtractor.DocumentData(
                    saved.getString("sourceName"),
                    saved.getString("kind"),
                    saved.optLong("fileSize", -1L),
                    saved.optInt("pageCount", 0),
                    saved.getString("text")
            );
            bankName = saved.optString("bankName", stripExtension(document.getDisplayName()));
            chapterChoices.clear();
            JSONArray chapters = saved.getJSONArray("chapters");
            for (int i = 0; i < chapters.length(); i++) {
                JSONObject item = chapters.getJSONObject(i);
                chapterChoices.add(new ChapterChoice(
                        item.getString("title"), item.getString("text"),
                        item.optBoolean("selected", true), item.optInt("questionCount", 0)
                ));
            }
            typeQuotas.clear();
            JSONObject types = saved.getJSONObject("typeQuotas");
            typeQuotas.put(MaterialQuestionRules.SINGLE, types.optInt(MaterialQuestionRules.SINGLE));
            typeQuotas.put(MaterialQuestionRules.MULTI, types.optInt(MaterialQuestionRules.MULTI));
            typeQuotas.put(MaterialQuestionRules.JUDGE, types.optInt(MaterialQuestionRules.JUDGE));
            typeQuotas.put(MaterialQuestionRules.FILL_BLANK, types.optInt(MaterialQuestionRules.FILL_BLANK));
            typeQuotas.put(MaterialQuestionRules.MATCHING, types.optInt(MaterialQuestionRules.MATCHING));
            questions = copyJson(saved.optJSONArray("questions"));
            unresolvedQuestions = copyJson(saved.optJSONArray("unresolvedQuestions"));
            imageRolesConfirmed = saved.optBoolean("imageRolesConfirmed", false);
            convertedQuestionBank = saved.optBoolean("convertedQuestionBank", false);
            automaticAttemptsExhausted = saved.optBoolean("automaticAttemptsExhausted", false);
            plan = buildPlan();
            JSONObject savedMatrix = saved.optJSONObject("typeMatrix");
            if (savedMatrix != null) {
                Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
                List<MaterialGenerationPlan.ChapterPlan> selected = new ArrayList<>();
                for (ChapterChoice choice : chapterChoices) {
                    if (!choice.selected || choice.questionCount <= 0) {
                        continue;
                    }
                    selected.add(new MaterialGenerationPlan.ChapterPlan(
                            choice.title, choice.text, choice.questionCount
                    ));
                    JSONObject rowJson = savedMatrix.optJSONObject(choice.title);
                    Map<String, Integer> row = new LinkedHashMap<>();
                    row.put(MaterialQuestionRules.SINGLE,
                            rowJson == null ? 0 : rowJson.optInt(MaterialQuestionRules.SINGLE));
                    row.put(MaterialQuestionRules.MULTI,
                            rowJson == null ? 0 : rowJson.optInt(MaterialQuestionRules.MULTI));
                    row.put(MaterialQuestionRules.JUDGE,
                            rowJson == null ? 0 : rowJson.optInt(MaterialQuestionRules.JUDGE));
                    row.put(MaterialQuestionRules.FILL_BLANK,
                            rowJson == null ? 0 : rowJson.optInt(MaterialQuestionRules.FILL_BLANK));
                    row.put(MaterialQuestionRules.MATCHING,
                            rowJson == null ? 0 : rowJson.optInt(MaterialQuestionRules.MATCHING));
                    matrix.put(choice.title, row);
                }
                plan = new MaterialGenerationPlan(
                        bankName,
                        document.getDisplayName(),
                        selected,
                        typeQuotas,
                        matrix
                );
            }
            if (questions.length() > 0) {
                MaterialQuestionGenerator.PlanValidationResult validation =
                        MaterialQuestionGenerator.validatePlan(questions, plan);
                questions = copyJson(validation.getAccepted());
                generationResult = new MaterialQuestionGenerator.GenerationResult(
                        saved.optInt("returnedCount", questions.length()),
                        saved.optInt("repairRounds", 0), validation
                );
                renderPreview();
            } else {
                renderPlan();
            }
        } catch (Exception e) {
            draftStore.clear();
            renderWelcome();
            showError("草稿已失效", e);
        }
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.brand_card));
        card.setStrokeColor(getColor(R.color.brand_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        card.setCardElevation(0f);
        return card;
    }

    private MaterialCardView softCard() {
        MaterialCardView card = card();
        card.setCardBackgroundColor(getColor(R.color.brand_primary_soft));
        card.setStrokeColor(getColor(R.color.brand_primary_soft));
        return card;
    }

    private MaterialCardView successCard() {
        MaterialCardView card = card();
        card.setCardBackgroundColor(Color.rgb(235, 249, 242));
        card.setStrokeColor(getColor(R.color.brand_success));
        return card;
    }

    private MaterialCardView warningCard() {
        MaterialCardView card = card();
        card.setCardBackgroundColor(Color.rgb(255, 248, 235));
        card.setStrokeColor(Color.rgb(232, 165, 62));
        return card;
    }

    private TextView metric(String label, int value) {
        TextView text = new TextView(this);
        text.setText(value + "\n" + label);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(getColor(R.color.brand_ink));
        text.setTextSize(12);
        text.setPadding(dp(2), dp(10), dp(2), dp(10));
        text.setBackgroundColor(getColor(R.color.brand_card));
        return text;
    }

    private View pipelineRow(boolean complete, String title, String detail) {
        LinearLayout row = horizontal(dp(10), Gravity.TOP);
        TextView dot = badge(complete ? "✓" : "·", complete
                ? getColor(R.color.brand_success)
                : getColor(R.color.brand_primary));
        row.addView(dot, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout copy = vertical(0);
        copy.addView(titleText(title));
        copy.addView(bodyText(detail));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(8);
        params.bottomMargin = dp(12);
        row.addView(copy, params);
        return row;
    }

    private TextView badge(String value, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(14);
        text.setGravity(Gravity.CENTER);
        text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
        text.setBackgroundColor(getColor(R.color.brand_primary_soft));
        return text;
    }

    private TextView statusText(String value, boolean success) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(success ? getColor(R.color.brand_success) : getColor(R.color.brand_primary));
        text.setTextSize(12);
        text.setPadding(dp(8), dp(5), dp(8), dp(5));
        text.setBackgroundColor(success ? Color.rgb(235, 249, 242) : getColor(R.color.brand_primary_soft));
        return text;
    }

    private TextView titleText(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(getColor(R.color.brand_ink));
        text.setTextSize(16);
        text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
        return text;
    }

    private TextView sectionText(String value) {
        TextView text = titleText(value);
        text.setTextSize(18);
        return text;
    }

    private TextView bodyText(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(getColor(R.color.brand_text_secondary));
        text.setTextSize(14);
        text.setLineSpacing(dp(2), 1f);
        return text;
    }

    private TextView actionText(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(getColor(R.color.brand_primary));
        text.setTextSize(14);
        text.setPadding(dp(8), dp(8), dp(8), dp(8));
        return text;
    }

    private void addSectionTitle(String value) {
        content.addView(sectionText(value), bottomMargin(dp(8)));
    }

    private EditText editText(String value, String hint) {
        EditText edit = new EditText(this);
        edit.setText(value == null || "null".equals(value) ? "" : value);
        edit.setHint(hint);
        edit.setTextColor(getColor(R.color.brand_ink));
        edit.setHintTextColor(getColor(R.color.brand_text_muted));
        edit.setTextSize(15);
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        edit.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_primary)));
        return edit;
    }

    private EditText numericEdit(int value) {
        EditText edit = editText(String.valueOf(Math.max(0, value)), "0");
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setGravity(Gravity.CENTER);
        edit.setSelectAllOnFocus(true);
        return edit;
    }

    private MaterialButton primaryButton(String value) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setTextSize(15);
        return button;
    }

    private MaterialButton secondaryButton(String value) {
        MaterialButton button = primaryButton(value);
        button.setTextColor(getColor(R.color.brand_primary));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_card)));
        button.setStrokeColor(ColorStateList.valueOf(getColor(R.color.brand_primary)));
        button.setStrokeWidth(dp(1));
        return button;
    }

    private MaterialButton compactButton(String value) {
        MaterialButton button = secondaryButton(value);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.brand_border));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ));
        return divider;
    }

    private static LinearLayout verticalStatic(android.content.Context context, int padding) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(padding, padding, padding, padding);
        return result;
    }

    private LinearLayout vertical(int padding) {
        return verticalStatic(this, padding);
    }

    private LinearLayout horizontal(int spacing, int gravity) {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(gravity);
        if (spacing > 0) {
            result.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            android.graphics.drawable.ColorDrawable gap = new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
            result.setDividerDrawable(gap);
            result.setDividerPadding(spacing);
        }
        return result;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    private LinearLayout.LayoutParams weightedButton(float weight, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), weight);
        params.leftMargin = left;
        params.rightMargin = right;
        return params;
    }

    private LinearLayout.LayoutParams topMargin(int margin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = margin;
        return params;
    }

    private LinearLayout.LayoutParams bottomMargin(int margin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = margin;
        return params;
    }

    private TextWatcher simpleWatcher(ValueChanged listener) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                listener.onChanged(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
    }

    private void resetTypeQuotas(int total) {
        typeQuotas.clear();
        typeQuotas.putAll(MaterialGenerationPlan.recommendTypes(total));
    }

    private static int parseCount(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int sum(Map<String, Integer> values) {
        int total = 0;
        for (int value : values.values()) {
            total += value;
        }
        return total;
    }

    private static String stripExtension(String name) {
        String value = name == null ? "资料生成题库" : name.trim();
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static JSONArray copyJson(JSONArray source) {
        try {
            return source == null ? new JSONArray() : new JSONArray(source.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static String answerText(JSONObject question) {
        JSONArray answers = question.optJSONArray("answers");
        if (answers == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < answers.length(); i++) {
            if (result.length() > 0) {
                result.append("、");
            }
            result.append(answers.optString(i));
        }
        return result.toString();
    }

    private static String optionsForEdit(JSONObject question) {
        JSONArray options = question.optJSONArray("options");
        if (options == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < options.length(); i++) {
            JSONObject item = options.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(item.optString("key")).append(" | ").append(item.optString("text"));
        }
        return result.toString();
    }

    private static String matchingItemsForEdit(JSONObject question, String side) {
        JSONObject matching = question.optJSONObject("matching");
        JSONArray items = matching == null ? null : matching.optJSONArray(side);
        if (items == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (result.length() > 0) result.append('\n');
            result.append(item.optString("id")).append(" | ").append(item.optString("text"));
        }
        return result.toString();
    }

    private void showDocxPreview(ImageView preview, String reference) {
        try {
            byte[] bytes = DocxMediaStore.read(this, reference);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = QuestionImageStore.calculateInSampleSize(
                    bounds.outWidth, bounds.outHeight, 600);
            preview.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options));
        } catch (Exception error) {
            preview.setImageResource(android.R.drawable.ic_dialog_alert);
            Toast.makeText(this, "无法预览原卷图片：" + error.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> unassignedDocxImages() {
        List<String> remaining = new ArrayList<>();
        try {
            remaining.addAll(DocxMediaStore.list(this));
            for (int i = 0; i < questions.length(); i++) {
                JSONObject question = questions.optJSONObject(i);
                if (question == null) continue;
                remaining.remove(question.optString("image", ""));
                JSONArray answerImages = question.optJSONArray("explanationImages");
                if (answerImages != null) for (int j = 0; j < answerImages.length(); j++) {
                    remaining.remove(answerImages.optString(j));
                }
            }
        } catch (Exception ignored) { }
        return remaining;
    }

    private static String matchingPairsForEdit(JSONObject question) {
        JSONObject matching = question.optJSONObject("matching");
        JSONArray pairs = matching == null ? null : matching.optJSONArray("correctPairs");
        if (pairs == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pairs.length(); i++) {
            JSONObject pair = pairs.optJSONObject(i);
            if (pair == null) continue;
            if (result.length() > 0) result.append('\n');
            result.append(pair.optString("leftId")).append(" | ").append(pair.optString("rightId"));
        }
        return result.toString();
    }

    private static List<MatchingData.Item> parseMatchingItems(String lines) {
        List<MatchingData.Item> items = new ArrayList<>();
        for (String line : lines.split("\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\s*[|｜]\\s*", 2);
            if (parts.length != 2) throw new IllegalArgumentException("连线项目格式应为 ID | 内容");
            items.add(new MatchingData.Item(parts[0], parts[1]));
        }
        return items;
    }

    private static List<MatchingData.Pair> parseMatchingPairs(String lines) {
        List<MatchingData.Pair> pairs = new ArrayList<>();
        for (String line : lines.split("\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\s*[|｜]\\s*", 2);
            if (parts.length != 2) throw new IllegalArgumentException("标准连线格式应为 左ID | 右ID");
            pairs.add(new MatchingData.Pair(parts[0], parts[1]));
        }
        return pairs;
    }

    private static int typeIndex(String type) {
        if (MaterialQuestionRules.MULTI.equals(type)) {
            return 1;
        }
        if (MaterialQuestionRules.JUDGE.equals(type)) {
            return 2;
        }
        if (MaterialQuestionRules.FILL_BLANK.equals(type)) {
            return 3;
        }
        if (MaterialQuestionRules.MATCHING.equals(type)) {
            return 4;
        }
        return 0;
    }

    private static String typeLabel(String type) {
        if (MaterialQuestionRules.SINGLE.equals(type)) {
            return "单选题";
        }
        if (MaterialQuestionRules.MULTI.equals(type)) {
            return "多选题";
        }
        if (MaterialQuestionRules.JUDGE.equals(type)) {
            return "判断题";
        }
        if (MaterialQuestionRules.MATCHING.equals(type)) {
            return "连线题";
        }
        return "填空题";
    }

    private static String missingText(Map<String, Integer> missing) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : missing.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            if (result.length() > 0) {
                result.append(" · ");
            }
            result.append(typeLabel(entry.getKey())).append(' ').append(entry.getValue()).append(" 题");
        }
        return result.length() == 0 ? "无" : result.toString();
    }

    private static Map<String, Map<String, Integer>> mutableMissing(
            Map<String, Map<String, Integer>> source
    ) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : source.entrySet()) {
            result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return result;
    }

    private static String firstMissingChapter(
            Map<String, Map<String, Integer>> missing,
            String type
    ) {
        if (type == null) {
            return null;
        }
        for (Map.Entry<String, Map<String, Integer>> entry : missing.entrySet()) {
            if (entry.getValue().getOrDefault(type, 0) > 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void showError(String title, Exception error) {
        String message = error == null || error.getMessage() == null
                ? "发生未知错误"
                : error.getMessage();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface ValueChanged {
        void onChanged(String value);
    }

    private static final class ChapterChoice {
        final String title;
        final String text;
        boolean selected;
        int questionCount;

        ChapterChoice(String title, String text, boolean selected, int questionCount) {
            this.title = title;
            this.text = text;
            this.selected = selected;
            this.questionCount = questionCount;
        }
    }
}
