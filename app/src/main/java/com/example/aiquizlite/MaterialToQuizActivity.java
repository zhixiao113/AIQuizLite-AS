package com.example.aiquizlite;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
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
        PLAN,
        GENERATING,
        PREVIEW
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String[]> documentPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::processDocument);
    private final List<ChapterChoice> chapterChoices = new ArrayList<>();
    private final Map<String, Integer> typeQuotas = new LinkedHashMap<>();

    private MaterialDraftStore draftStore;
    private MaterialDocumentExtractor.DocumentData document;
    private MaterialGenerationPlan plan;
    private MaterialQuestionGenerator.GenerationResult generationResult;
    private MaterialQuestionGenerator.Snapshot lastSnapshot;
    private JSONArray questions = new JSONArray();
    private Stage stage = Stage.WELCOME;
    private String bankName = "";
    private boolean automaticAttemptsExhausted;
    private boolean suppressDraftSave;
    private boolean convertedQuestionBank;
    private boolean openPickerAfterAiSetup;
    private android.net.Uri pendingDocumentUri;
    private LinearLayout content;
    private LinearLayout footer;
    private TextView planTotalsText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        getWindow().setStatusBarColor(getColor(R.color.brand_surface));
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        draftStore = new MaterialDraftStore(this);
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
        if (externalUri != null) {
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
    protected void onDestroy() {
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

        MaterialButton json = secondaryButton("导入已有 JSON（兼容入口）");
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
        renderLoading("正在读取并分析资料", "本地提取文字后由 AI 判断资料类型和建议题量");
        executor.execute(() -> {
            try {
                MaterialDocumentExtractor.DocumentData result =
                        new MaterialDocumentExtractor().extract(this, uri);
                List<MaterialTextRules.Chapter> chapters =
                        MaterialTextRules.detectChapters(result.getText());
                if (MaterialTextRules.countCharacters(chapters)
                        > MaterialTextRules.MAX_SELECTED_CHARACTERS) {
                    throw new IllegalArgumentException("资料文字超过 10 万字，请拆分文件后再导入，避免产生不可控的 Token 消耗");
                }
                MaterialDocumentAnalysis analysis = new MaterialQuestionGenerator().analyzeDocument(
                        config, result.getDisplayName(), chapters
                );
                runOnUiThread(() -> showDocumentAnalysisDecision(result, chapters, analysis));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    renderWelcome();
                    showError("资料识别失败", e);
                });
            }
        });
    }

    private void showDocumentAnalysisDecision(
            MaterialDocumentExtractor.DocumentData result,
            List<MaterialTextRules.Chapter> chapters,
            MaterialDocumentAnalysis analysis
    ) {
        int confidence = (int) Math.round(analysis.getConfidence() * 100d);
        String detected = analysis.isQuestionBank() ? "现成题库" : "学习资料";
        String detail = analysis.isQuestionBank()
                ? "AI 估计约有 " + analysis.getDetectedQuestionCount() + " 道原题，将逐段识别并转换。"
                : "AI 将遍历全文知识点，再确定建议题量和题型。";
        Runnable learningAction = () -> startLearningPlanAnalysis(result, chapters);
        Runnable bankAction = () -> startQuestionBankConversion(result, chapters);
        new AlertDialog.Builder(this)
                .setTitle("AI 判断：" + detected)
                .setMessage(analysis.getReason() + "\n\n" + detail + "\n判断置信度：" + confidence + "%\n\n如果判断有误，可选择另一种处理方式。")
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
        int requests = MaterialQuestionGenerator.estimateLearningPlanRequests(chapters);
        renderLoading("AI 正在盘点知识点", "预计 " + requests + " 次分析请求 · 完成后可调整题量");
        executor.execute(() -> {
            try {
                MaterialGenerationPlan recommended = new MaterialQuestionGenerator().recommendLearningPlan(
                        config,
                        bankName,
                        result.getDisplayName(),
                        chapters,
                        (completed, total, message) -> runOnUiThread(() ->
                                renderLoading(
                                        "AI 正在盘点知识点",
                                        completed + " / " + total + " 次请求 · " + message
                                ))
                );
                runOnUiThread(() -> applyDocument(result, recommended));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    renderWelcome();
                    showError("知识点分析失败", e);
                });
            }
        });
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
        generationResult = null;
        automaticAttemptsExhausted = false;
        stage = Stage.WELCOME;
        int requests = MaterialQuestionGenerator.estimateQuestionBankRequests(chapters);
        renderLoading("AI 正在识别题库", "预计 " + requests + " 次请求 · 正在准备分段转换");
        executor.execute(() -> {
            try {
                MaterialQuestionGenerator.QuestionBankConversionResult converted =
                        new MaterialQuestionGenerator().convertQuestionBank(
                                config,
                                bankName,
                                chapters,
                                (completed, total, message) -> runOnUiThread(() ->
                                        renderLoading(
                                                "AI 正在识别题库",
                                                completed + " / " + total + " 次请求 · " + message
                                        ))
                        );
                runOnUiThread(() -> applyQuestionBankConversion(chapters, converted));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    stage = Stage.WELCOME;
                    renderWelcome();
                    showError("题库转换失败", e);
                });
            }
        });
    }

    private void applyQuestionBankConversion(
            List<MaterialTextRules.Chapter> chapters,
            MaterialQuestionGenerator.QuestionBankConversionResult converted
    ) {
        try {
            questions = copyJson(converted.getAccepted());
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

        beginScreen("校对工作台", questions.length() + " 题 · 保存前最后检查",
                view -> {
                    if (convertedQuestionBank) {
                        renderWelcome();
                    } else {
                        renderPlan();
                    }
                });
        addSteps(4);
        MaterialCardView summary = validation.isComplete() ? successCard() : warningCard();
        LinearLayout summaryBody = vertical(dp(16));
        summaryBody.addView(titleText(validation.isComplete()
                ? (convertedQuestionBank ? "✓ AI 原题识别与格式校验完成" : "✓ 两轮严格校验通过")
                : "还缺 " + validation.getMissingCount() + " 题"));
        summaryBody.addView(bodyText(
                questions.length() + " / " + plan.getTargetCount() + " 有效 · 移除重复 "
                        + generationResult.getRemovedDuplicateCount() + " 题 · 无效 "
                        + generationResult.getRemovedInvalidCount() + " 题"
        ));
        summary.addView(summaryBody);
        content.addView(summary, bottomMargin(dp(16)));

        if (!validation.isComplete()) {
            MaterialCardView gap = warningCard();
            LinearLayout gapBody = vertical(dp(14));
            gapBody.addView(titleText("缺口：" + missingText(validation.aggregateMissingByType())));
            gapBody.addView(bodyText("保存按钮已锁定，必须补齐并重新通过完整校验。"));
            LinearLayout choices = horizontal(dp(8), Gravity.END);
            MaterialButton adjust = compactButton("调整章节/题量");
            adjust.setOnClickListener(view -> renderPlan());
            choices.addView(adjust);
            MaterialButton lower = compactButton("降低到当前题量");
            lower.setOnClickListener(view -> adoptCurrentCounts());
            choices.addView(lower);
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

        if (!validation.isComplete() && automaticAttemptsExhausted) {
            addManualFallback(validation);
        }

        MaterialButton draft = secondaryButton("保存草稿");
        draft.setOnClickListener(view -> {
            persistDraftQuietly();
            Toast.makeText(this, "草稿已保存", Toast.LENGTH_SHORT).show();
        });
        footer.addView(draft, weightedButton(1f, dp(6), 0));
        MaterialButton action = primaryButton(validation.isComplete() ? "保存为题库" : "继续自动补齐");
        if (validation.isComplete()) {
            action.setOnClickListener(view -> saveAsQuestionBank());
        } else {
            action.setOnClickListener(view -> startGeneration(true));
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
        body.addView(bodyText("正确答案：" + answerText(question)), topMargin(dp(10)));
        String explanation = question.optString("explanation", "").trim();
        if (!explanation.isEmpty() && !"null".equals(explanation)) {
            body.addView(bodyText("解析：" + explanation), topMargin(dp(6)));
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
        Map<String, Map<String, Integer>> actualMatrix = new LinkedHashMap<>();
        List<MaterialGenerationPlan.ChapterPlan> loweredChapters = new ArrayList<>();
        for (ChapterChoice choice : chapterChoices) {
            int count = 0;
            Map<String, Integer> row = new LinkedHashMap<>();
            row.put(MaterialQuestionRules.SINGLE, 0);
            row.put(MaterialQuestionRules.MULTI, 0);
            row.put(MaterialQuestionRules.JUDGE, 0);
            row.put(MaterialQuestionRules.FILL_BLANK, 0);
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
        Spinner type = new Spinner(this);
        String[] typeLabels = {"单选题", "多选题", "判断题", "填空题"};
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
                                existing, type.getSelectedItemPosition(), stem.getText().toString(),
                                options.getText().toString(), answers.getText().toString(),
                                explanation.getText().toString()
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
            String stem,
            String optionsText,
            String answersText,
            String explanation
    ) throws Exception {
        String[] types = {
                MaterialQuestionRules.SINGLE,
                MaterialQuestionRules.MULTI,
                MaterialQuestionRules.JUDGE,
                MaterialQuestionRules.FILL_BLANK
        };
        String selectedType = types[Math.max(0, Math.min(types.length - 1, typeIndex))];
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
        result.put("stem", stem.trim());
        result.put("options", optionArray);
        result.put("answers", answerArray);
        result.put("explanation", explanation.trim().isEmpty() ? JSONObject.NULL : explanation.trim());
        result.put("qualityState", "人工编辑");
        return result;
    }

    private void replaceQuestion(int index, JSONObject updated) throws Exception {
        JSONArray combined = new JSONArray();
        for (int i = 0; i < questions.length(); i++) {
            combined.put(i == index ? updated : questions.getJSONObject(i));
        }
        if (convertedQuestionBank) {
            applyConvertedEdits(combined);
            return;
        }
        MaterialQuestionGenerator.PlanValidationResult checked =
                MaterialQuestionGenerator.validatePlan(combined, plan);
        questions = copyJson(checked.getAccepted());
        generationResult = new MaterialQuestionGenerator.GenerationResult(
                generationResult == null ? combined.length() : generationResult.getReturnedCount(),
                generationResult == null ? 0 : generationResult.getRepairRounds(),
                checked
        );
        persistDraftQuietly();
        renderPreview();
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
            JSONArray finalQuestions = new JSONArray(validation.getAccepted().toString());
            for (int i = 0; i < finalQuestions.length(); i++) {
                JSONObject item = finalQuestions.getJSONObject(i);
                item.remove("sourceSnippet");
                item.remove("qualityState");
                item.put("sourceName", document.getDisplayName());
            }
            QuestionRepository.QuestionBank created = QuestionRepository.importQuestionBank(
                    this, plan.getBankName(), finalQuestions
            );
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

    private void renderLoading(String title, String subtitle) {
        beginScreen(title, subtitle, view -> finish());
        ProgressBar progress = new ProgressBar(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(56), dp(56));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(80);
        content.addView(progress, params);
    }

    private void beginScreen(String title, String subtitle, View.OnClickListener backAction) {
        LinearLayout root = vertical(0);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));
        root.setBackgroundColor(getColor(R.color.brand_surface));
        setContentView(root);

        LinearLayout header = horizontal(dp(12), Gravity.CENTER_VERTICAL);
        MaterialButton back = compactButton("‹");
        back.setTextSize(28);
        back.setOnClickListener(backAction);
        header.addView(back, new LinearLayout.LayoutParams(dp(54), dp(54)));
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
            questions = copyJson(saved.optJSONArray("questions"));
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
