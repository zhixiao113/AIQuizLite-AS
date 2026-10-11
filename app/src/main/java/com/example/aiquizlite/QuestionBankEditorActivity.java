package com.example.aiquizlite;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class QuestionBankEditorActivity extends AppCompatActivity {
    private static final long DRAG_HOLD_MS = 650L;
    private static final String[] TYPE_CODES = {"single", "multi", "judge", "fill_blank", "matching"};
    private static final String[] TYPE_LABELS = {"单选题", "多选题", "判断题", "填空题", "连线题"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Question> workingQuestions = new ArrayList<>();
    private final Set<String> deletedQuestionIds = new HashSet<>();
    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::handlePickedImage);

    private QuestionRepository.QuestionBank questionBank;
    private String questionBankId;
    private EditText searchEdit;
    private EditText nameEdit;
    private TextInputLayout nameLayout;
    private TextView questionCount;
    private TextView sortingHint;
    private LinearLayout listContainer;
    private ScrollView listScrollView;
    private boolean dirty;
    private View draggingRow;
    private int dragFromIndex = -1;
    private int dragTargetIndex = -1;
    private float dragOriginY;
    private ImageEditSession activeImageEditSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        questionBankId = getIntent().getStringExtra(QuizActivity.EXTRA_QUESTION_BANK);
        questionBank = QuestionRepository.findQuestionBank(this, questionBankId);
        if (!questionBank.isImported()) {
            Toast.makeText(this, "内置题库需要先复制后编辑", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        workingQuestions.addAll(QuestionRepository.load(this, questionBankId));
        buildLayout();
        renderQuestions();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExitIfNeeded();
            }
        });
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.brand_card));
        root.setFocusableInTouchMode(true);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(root);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightNavigationBars(true);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(bars.left + dp(16), bars.top + dp(4),
                    bars.right + dp(16), Math.max(bars.bottom, keyboard));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        MaterialButton backButton = actionButton("", R.color.brand_ink, R.color.brand_card,
                R.drawable.ic_editor_back);
        backButton.setContentDescription("返回");
        backButton.setIconPadding(0);
        backButton.setOnClickListener(view -> confirmExitIfNeeded());
        header.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText("编辑题库");
        title.setTextColor(getColor(R.color.brand_ink));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        header.addView(title, titleParams);

        MaterialButton saveButton = actionButton("保存", R.color.brand_card,
                R.color.brand_primary, 0);
        saveButton.setOnClickListener(view -> saveQuestionBank());
        header.addView(saveButton, wrapWrap());

        nameLayout = new TextInputLayout(this);
        nameLayout.setHint("题库名称");
        nameLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        nameLayout.setBoxCornerRadii(dp(12), dp(12), dp(12), dp(12));
        nameLayout.setBoxStrokeColor(getColor(R.color.brand_primary));
        nameLayout.setDefaultHintTextColor(ColorStateList.valueOf(getColor(R.color.brand_text_secondary)));
        nameLayout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        nameLayout.setEndIconDrawable(R.drawable.ic_editor_edit);
        nameLayout.setEndIconContentDescription("修改题库名称");
        nameEdit = new TextInputEditText(nameLayout.getContext());
        nameEdit.setId(R.id.editor_bank_name);
        nameEdit.setText(questionBank.getDisplayName());
        nameEdit.setTextColor(getColor(R.color.brand_ink));
        nameEdit.setTextSize(16);
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameEdit.setSingleLine(false);
        nameEdit.setMaxLines(3);
        nameEdit.setHorizontallyScrolling(false);
        nameEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        nameLayout.addView(nameEdit, matchWrap());
        nameLayout.setEndIconOnClickListener(view -> {
            nameEdit.requestFocus();
            nameEdit.setSelection(nameEdit.length());
            WindowCompat.getInsetsController(getWindow(), nameEdit).show(WindowInsetsCompat.Type.ime());
        });
        LinearLayout.LayoutParams nameParams = matchWrap();
        nameParams.topMargin = dp(12);
        root.addView(nameLayout, nameParams);
        nameEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                nameLayout.setError(null);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        TextView hint = new TextView(this);
        hint.setText("名称和题目修改后统一保存");
        hint.setTextColor(getColor(R.color.brand_text_secondary));
        hint.setTextSize(13);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(6);
        root.addView(hint, hintParams);

        searchEdit = new EditText(this);
        searchEdit.setId(R.id.editor_search);
        searchEdit.setHint("搜索题干、题号或试卷");
        searchEdit.setSingleLine(true);
        searchEdit.setTextSize(14);
        searchEdit.setTextColor(getColor(R.color.brand_ink));
        searchEdit.setHintTextColor(getColor(R.color.brand_text_secondary));
        searchEdit.setBackground(roundedBackground(R.color.brand_surface, 12));
        searchEdit.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchEdit.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_editor_search, 0, 0, 0);
        searchEdit.setCompoundDrawablePadding(dp(10));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        searchParams.topMargin = dp(12);
        root.addView(searchEdit, searchParams);
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderQuestions();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolsParams = matchWrap();
        toolsParams.topMargin = dp(10);
        root.addView(tools, toolsParams);
        questionCount = label("题目");
        questionCount.setTextSize(17);
        questionCount.setTextColor(getColor(R.color.brand_ink));
        questionCount.setPadding(0, 0, 0, 0);
        tools.addView(questionCount, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton addButton = actionButton("新增题目", R.color.brand_primary,
                R.color.brand_primary_soft, R.drawable.ic_editor_add);
        addButton.setOnClickListener(view -> showQuestionDialog(null));
        LinearLayout.LayoutParams addParams = wrapWrap();
        addParams.leftMargin = dp(10);
        tools.addView(addButton, addParams);

        listScrollView = new ScrollView(this);
        listScrollView.setClipToPadding(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        );
        scrollParams.topMargin = dp(12);
        root.addView(listScrollView, scrollParams);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listScrollView.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        sortingHint = new TextView(this);
        sortingHint.setTextColor(getColor(R.color.brand_text_secondary));
        sortingHint.setTextSize(12);
        sortingHint.setGravity(Gravity.CENTER);
        sortingHint.setPadding(0, dp(8), 0, dp(8));
        root.addView(sortingHint, matchWrap());
        root.requestFocus();
    }

    private void renderQuestions() {
        if (listContainer == null) {
            return;
        }
        String query = searchEdit == null ? "" : searchEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
        listContainer.removeAllViews();
        for (int i = 0; i < workingQuestions.size(); i++) {
            Question question = workingQuestions.get(i);
            if (!matchesQuery(question, query)) {
                continue;
            }
            listContainer.addView(createQuestionRow(question, i));
        }
        int visibleCount = listContainer.getChildCount();
        questionCount.setText(query.isEmpty() ? "题目 · " + workingQuestions.size()
                : "匹配 " + visibleCount + " / " + workingQuestions.size());
        sortingHint.setText(query.isEmpty() ? "长按题目或右侧手柄拖动排序" : "清空搜索后可拖动排序");
        if (listContainer.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("没有匹配的题目，试试其他关键词");
            empty.setTextColor(getColor(R.color.brand_text_secondary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(40));
            listContainer.addView(empty, matchWrap());
        }
    }

    private boolean matchesQuery(Question question, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (question.isMatching()) {
            for (MatchingData.Item item : question.getMatching().getLeftItems()) {
                if (item.getText().toLowerCase(Locale.ROOT).contains(query)) return true;
            }
            for (MatchingData.Item item : question.getMatching().getRightItems()) {
                if (item.getText().toLowerCase(Locale.ROOT).contains(query)) return true;
            }
        }
        return question.getStem().toLowerCase(Locale.ROOT).contains(query)
                || question.getPaper().toLowerCase(Locale.ROOT).contains(query)
                || question.getId().toLowerCase(Locale.ROOT).contains(query)
                || String.valueOf(question.getNumberInPaper()).contains(query);
    }

    private View createQuestionRow(Question question, int workingIndex) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.brand_card));
        card.setRadius(dp(12));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(getColor(R.color.brand_border));
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(6), dp(14), dp(6));
        card.addView(row);

        LinearLayout metadata = new LinearLayout(this);
        metadata.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(metadata, matchWrap());
        TextView number = chip(String.format(Locale.ROOT, "%02d", workingIndex + 1));
        metadata.addView(number, wrapWrap());
        TextView type = chip(question.typeLabel());
        LinearLayout.LayoutParams typeParams = wrapWrap();
        typeParams.leftMargin = dp(8);
        metadata.addView(type, typeParams);
        metadata.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView handle = new ImageView(this);
        handle.setImageResource(R.drawable.ic_editor_drag);
        handle.setPadding(dp(14), dp(14), dp(14), dp(14));
        handle.setContentDescription("长按拖动第 " + (workingIndex + 1) + " 题排序");
        metadata.addView(handle, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView summary = new TextView(this);
        summary.setText(question.getStem());
        summary.setTextColor(getColor(R.color.brand_ink));
        summary.setTextSize(16);
        summary.setLineSpacing(dp(3), 1f);
        summary.setMaxLines(4);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(summary, matchWrap());
        TextView source = new TextView(this);
        source.setText(question.getPaper() + " · 原卷第 " + question.getNumberInPaper() + " 题");
        source.setTextSize(12);
        source.setTextColor(getColor(R.color.brand_text_secondary));
        source.setMaxLines(1);
        source.setEllipsize(TextUtils.TruncateAt.END);
        source.setPadding(0, dp(6), 0, dp(10));
        row.addView(source, matchWrap());
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.brand_border_light));
        row.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(actions, matchWrap());

        MaterialButton editButton = actionButton("编辑", R.color.brand_text_secondary,
                R.color.brand_surface, R.drawable.ic_editor_edit);
        editButton.setOnClickListener(view -> showQuestionDialog(question));
        actions.addView(editButton, wrapWrap());

        MaterialButton deleteButton = actionButton("删除", R.color.brand_error_dark,
                R.color.brand_error_soft, R.drawable.ic_delete_compact);
        deleteButton.setOnClickListener(view -> confirmDeleteQuestion(question));
        LinearLayout.LayoutParams deleteParams = wrapWrap();
        deleteParams.leftMargin = dp(10);
        actions.addView(deleteButton, deleteParams);

        bindDrag(summary, card, workingIndex);
        bindDrag(handle, card, workingIndex);
        return card;
    }

    private void bindDrag(View handle, View row, int workingIndex) {
        float[] downY = new float[1];
        boolean[] armed = new boolean[1];
        Runnable begin = () -> {
            armed[0] = false;
            if (!searchEdit.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "请先清空搜索再排序", Toast.LENGTH_SHORT).show();
                return;
            }
            draggingRow = row;
            dragFromIndex = workingIndex;
            dragTargetIndex = workingIndex;
            dragOriginY = 0f;
            row.setElevation(dp(14));
            row.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start();
            listScrollView.requestDisallowInterceptTouchEvent(true);
        };

        handle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = event.getRawY();
                    armed[0] = true;
                    handler.postDelayed(begin, DRAG_HOLD_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (draggingRow == row) {
                        float dy = event.getRawY() - downY[0];
                        if (dragOriginY == 0f) {
                            dragOriginY = dy;
                        }
                        row.setTranslationY(dy - dragOriginY);
                        dragTargetIndex = findDragTarget(row);
                        return true;
                    }
                    if (Math.abs(event.getRawY() - downY[0]) > dp(8) && armed[0]) {
                        armed[0] = false;
                        handler.removeCallbacks(begin);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (armed[0]) {
                        armed[0] = false;
                        handler.removeCallbacks(begin);
                    }
                    if (draggingRow == row) {
                        finishDrag(row);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private int findDragTarget(View row) {
        float center = row.getTop() + row.getTranslationY() + row.getHeight() / 2f;
        int target = 0;
        for (int i = 0; i < listContainer.getChildCount(); i++) {
            View child = listContainer.getChildAt(i);
            if (child == row) {
                continue;
            }
            if (center > child.getTop() + child.getHeight() / 2f) {
                target++;
            } else {
                break;
            }
        }
        return Math.max(0, Math.min(target, workingQuestions.size() - 1));
    }

    private void finishDrag(View row) {
        if (dragFromIndex >= 0 && dragTargetIndex >= 0 && dragFromIndex != dragTargetIndex) {
            Question moved = workingQuestions.remove(dragFromIndex);
            workingQuestions.add(dragTargetIndex, moved);
            dirty = true;
        }
        draggingRow = null;
        dragFromIndex = -1;
        dragTargetIndex = -1;
        row.setElevation(0);
        row.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(120)
                .withEndAction(this::renderQuestions).start();
        listScrollView.requestDisallowInterceptTouchEvent(false);
    }

    private void showQuestionDialog(Question existing) {
        boolean creating = existing == null;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(8));

        content.addView(label("题目"), matchWrap());
        EditText stemEdit = field("请输入题目", creating ? "" : existing.getStem(), true);
        content.addView(stemEdit, matchWrap());
        content.addView(label("试卷名称"), matchWrap());
        EditText paperEdit = field("请输入试卷名称", creating ? questionBank.getDisplayName() : existing.getPaper(), false);
        content.addView(paperEdit, matchWrap());
        content.addView(label("题序（原卷题号）"), matchWrap());
        EditText numberEdit = field(
                "请输入题序",
                String.valueOf(creating ? workingQuestions.size() + 1 : existing.getNumberInPaper()),
                false
        );
        numberEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(numberEdit, matchWrap());

        TextView typeTitle = label("题型");
        content.addView(typeTitle, matchWrap());
        Spinner typeSpinner = new Spinner(this);
        typeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS));
        String initialType = creating ? "single" : existing.getType();
        typeSpinner.setSelection(typeIndex(initialType));
        content.addView(typeSpinner, matchWrap());

        TextView optionsTitle = label("选项");
        content.addView(optionsTitle, matchWrap());
        EditText optionsEdit = field(
                "每行格式：A | 选项内容",
                creating ? "A | \nB | " : formatOptions(existing),
                true
        );
        content.addView(optionsEdit, matchWrap());
        TextView answersTitle = label("正确答案");
        content.addView(answersTitle, matchWrap());
        EditText answersEdit = field(
                "每行一个；填空题可填写多个可接受答案",
                creating ? "" : String.join("\n", existing.getAnswers()),
                true
        );
        content.addView(answersEdit, matchWrap());
        LinearLayout matchingFields = new LinearLayout(this);
        matchingFields.setOrientation(LinearLayout.VERTICAL);
        matchingFields.addView(label("左侧项目（每行：L1 | 内容）"), matchWrap());
        EditText leftEdit = field("L1 | 左侧内容", creating || !existing.isMatching()
                ? "" : formatMatchingItems(existing.getMatching().getLeftItems()), true);
        matchingFields.addView(leftEdit, matchWrap());
        matchingFields.addView(label("右侧项目（每行：R1 | 内容）"), matchWrap());
        EditText rightEdit = field("R1 | 右侧内容", creating || !existing.isMatching()
                ? "" : formatMatchingItems(existing.getMatching().getRightItems()), true);
        matchingFields.addView(rightEdit, matchWrap());
        matchingFields.addView(label("标准连线（每行：L1 | R1）"), matchWrap());
        EditText pairsEdit = field("L1 | R1", creating || !existing.isMatching()
                ? "" : formatMatchingPairs(existing.getMatching().getCorrectPairs()), true);
        matchingFields.addView(pairsEdit, matchWrap());
        Spinner matchingMode = new Spinner(this);
        matchingMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"左侧最多连接一次", "左侧可重复使用"}));
        matchingMode.setSelection(!creating && existing.isMatching()
                && MatchingData.LEFT_REUSABLE.equals(existing.getMatching().getMode()) ? 1 : 0);
        matchingFields.addView(matchingMode, matchWrap());
        content.addView(matchingFields, matchWrap());
        content.addView(label("解析"), matchWrap());
        EditText explanationEdit = field(
                "可留空",
                creating || existing.getExplanation() == null ? "" : existing.getExplanation(),
                true
        );
        content.addView(explanationEdit, matchWrap());

        content.addView(label("题目图片"), matchWrap());
        ImageEditSession imageSession = createImageEditSession(existing);
        content.addView(imageSession.card, matchWrap());
        content.addView(label("解析图片（提交后显示）"), matchWrap());
        LinearLayout explanationImageContainer = new LinearLayout(this);
        explanationImageContainer.setOrientation(LinearLayout.VERTICAL);
        List<ImageEditSession> explanationImageSessions = new ArrayList<>();
        if (!creating) {
            for (String reference : existing.getExplanationImages()) {
                ImageEditSession session = createImageEditSession(reference);
                explanationImageSessions.add(session);
                explanationImageContainer.addView(session.card, matchWrap());
            }
        }
        MaterialButton addExplanationImage = smallButton("添加解析图片");
        addExplanationImage.setOnClickListener(view -> {
            ImageEditSession session = createImageEditSession((String) null);
            explanationImageSessions.add(session);
            explanationImageContainer.addView(session.card, matchWrap());
        });
        content.addView(explanationImageContainer, matchWrap());
        content.addView(addExplanationImage, matchWrap());

        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int visibility = "fill_blank".equals(TYPE_CODES[position])
                        || "matching".equals(TYPE_CODES[position]) ? View.GONE : View.VISIBLE;
                optionsTitle.setVisibility(visibility);
                optionsEdit.setVisibility(visibility);
                answersTitle.setVisibility("matching".equals(TYPE_CODES[position]) ? View.GONE : View.VISIBLE);
                answersEdit.setVisibility("matching".equals(TYPE_CODES[position]) ? View.GONE : View.VISIBLE);
                matchingFields.setVisibility("matching".equals(TYPE_CODES[position]) ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(creating ? "新增题目" : "编辑题目")
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                Question updated = buildQuestionFromForm(
                        existing,
                        stemEdit,
                        paperEdit,
                        numberEdit,
                        typeSpinner,
                        optionsEdit,
                        answersEdit,
                        explanationEdit,
                        imageSession.imageReference,
                        leftEdit,
                        rightEdit,
                        pairsEdit,
                        matchingMode
                );
                if (imageSession.pendingUri != null) {
                    String importedImage = QuestionImageStore.importFromUri(
                            this,
                            questionBankId,
                            imageSession.pendingUri
                    );
                    updated = copyQuestionWithImage(updated, importedImage);
                }
                List<String> explanationImages = new ArrayList<>();
                for (ImageEditSession session : explanationImageSessions) {
                    if (session.pendingUri != null) {
                        explanationImages.add(QuestionImageStore.importFromUri(
                                this, questionBankId, session.pendingUri));
                    } else if (session.imageReference != null) {
                        explanationImages.add(session.imageReference);
                    }
                }
                updated = copyQuestionWithExplanationImages(updated, explanationImages);
                if (creating) {
                    workingQuestions.add(updated);
                } else {
                    int index = findQuestionIndex(existing.getId());
                    if (index >= 0) {
                        workingQuestions.set(index, updated);
                    }
                }
                dirty = true;
                renderQuestions();
                dialog.dismiss();
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.setOnDismissListener(ignored -> {
            if (activeImageEditSession == imageSession
                    || explanationImageSessions.contains(activeImageEditSession)) {
                activeImageEditSession = null;
            }
        });
        dialog.show();
    }

    private Question buildQuestionFromForm(
            Question existing,
            EditText stemEdit,
            EditText paperEdit,
            EditText numberEdit,
            Spinner typeSpinner,
            EditText optionsEdit,
            EditText answersEdit,
            EditText explanationEdit,
            String imageReference,
            EditText leftEdit,
            EditText rightEdit,
            EditText pairsEdit,
            Spinner matchingMode
    ) {
        String stem = stemEdit.getText().toString().trim();
        if (stem.isEmpty()) {
            throw new IllegalArgumentException("题干不能为空");
        }
        String paper = paperEdit.getText().toString().trim();
        if (paper.isEmpty()) {
            paper = questionBank.getDisplayName();
        }
        int number;
        try {
            number = Integer.parseInt(numberEdit.getText().toString().trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("原卷题号需要是数字");
        }
        String type = TYPE_CODES[typeSpinner.getSelectedItemPosition()];
        boolean matchingType = "matching".equals(type);
        List<String> answers = matchingType ? new ArrayList<>()
                : parseNonEmptyLines(answersEdit.getText().toString());
        if (!matchingType && answers.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个正确答案");
        }
        List<Question.Option> options = "fill_blank".equals(type) || matchingType
                ? new ArrayList<>()
                : parseOptions(optionsEdit.getText().toString());
        if (!"fill_blank".equals(type) && !matchingType) {
            Set<String> optionKeys = new HashSet<>();
            for (Question.Option option : options) {
                optionKeys.add(option.getKey());
            }
            for (String answer : answers) {
                if (!optionKeys.contains(answer)) {
                    throw new IllegalArgumentException("正确答案“" + answer + "”不在选项 key 中");
                }
            }
            if (("single".equals(type) || "judge".equals(type)) && answers.size() != 1) {
                throw new IllegalArgumentException("单选题和判断题只能有一个正确答案");
            }
        }
        String explanation = explanationEdit.getText().toString().trim();
        String id = existing == null
                ? "custom_" + System.currentTimeMillis() + "_" + workingQuestions.size()
                : existing.getId();
        MatchingData matching = matchingType ? new MatchingData(
                matchingMode.getSelectedItemPosition() == 1
                        ? MatchingData.LEFT_REUSABLE : MatchingData.ONE_TO_ONE,
                parseMatchingItems(leftEdit.getText().toString()),
                parseMatchingItems(rightEdit.getText().toString()),
                parseMatchingPairs(pairsEdit.getText().toString())) : null;
        return new Question(
                id,
                existing == null ? workingQuestions.size() + 1 : existing.getIndex(),
                paper,
                number,
                type,
                stem,
                options,
                answers,
                imageReference,
                explanation.isEmpty() ? null : explanation,
                matching,
                existing == null ? new ArrayList<>() : existing.getExplanationImages(),
                existing == null ? null : existing.getSourceChapter(),
                existing == null ? null : existing.getSourceName()
        );
    }

    private Question copyQuestionWithImage(Question question, String imageReference) {
        return new Question(
                question.getId(),
                question.getIndex(),
                question.getPaper(),
                question.getNumberInPaper(),
                question.getType(),
                question.getStem(),
                question.getOptions(),
                question.getAnswers(),
                imageReference,
                question.getExplanation(),
                question.getMatching(),
                question.getExplanationImages(), question.getSourceChapter(), question.getSourceName()
        );
    }

    private Question copyQuestionWithExplanationImages(Question question, List<String> images) {
        return new Question(question.getId(), question.getIndex(), question.getPaper(),
                question.getNumberInPaper(), question.getType(), question.getStem(),
                question.getOptions(), question.getAnswers(), question.getImage(),
                question.getExplanation(), question.getMatching(), images,
                question.getSourceChapter(), question.getSourceName());
    }

    private ImageEditSession createImageEditSession(Question existing) {
        return createImageEditSession(existing == null ? null : existing.getImage());
    }

    private ImageEditSession createImageEditSession(String imageReference) {
        ImageEditSession session = new ImageEditSession();
        session.imageReference = imageReference;

        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.brand_card));
        card.setStrokeColor(getColor(R.color.brand_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(12));
        card.setCardElevation(0);
        session.card = card;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(row);

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(getColor(R.color.brand_card_hover));
        LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(dp(92), dp(76));
        thumbnailParams.rightMargin = dp(12);
        row.addView(thumbnail, thumbnailParams);
        session.thumbnail = thumbnail;

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        row.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView fileName = new TextView(this);
        fileName.setTextColor(getColor(R.color.brand_ink));
        fileName.setTextSize(13);
        fileName.setMaxLines(1);
        fileName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fileName.setTypeface(fileName.getTypeface(), android.graphics.Typeface.BOLD);
        details.addView(fileName, matchWrap());
        session.fileName = fileName;

        TextView status = new TextView(this);
        status.setTextColor(getColor(R.color.brand_text_muted));
        status.setTextSize(12);
        details.addView(status, matchWrap());
        session.status = status;

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        details.addView(actions, matchWrap());

        MaterialButton chooseButton = smallButton("添加");
        chooseButton.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        chooseButton.setTextColor(getColor(R.color.brand_primary));
        chooseButton.setStrokeWidth(0);
        chooseButton.setOnClickListener(view -> {
            activeImageEditSession = session;
            pickImageLauncher.launch("image/*");
        });
        actions.addView(chooseButton, wrapWrap());
        session.chooseButton = chooseButton;

        MaterialButton deleteButton = smallButton("删除");
        deleteButton.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        deleteButton.setTextColor(getColor(R.color.brand_error));
        deleteButton.setStrokeWidth(0);
        deleteButton.setOnClickListener(view -> {
            session.pendingUri = null;
            session.imageReference = null;
            renderImageEditSession(session);
        });
        actions.addView(deleteButton, wrapWrap());
        session.deleteButton = deleteButton;

        renderImageEditSession(session);
        return session;
    }

    private void handlePickedImage(Uri uri) {
        if (uri == null || activeImageEditSession == null) {
            return;
        }
        activeImageEditSession.pendingUri = uri;
        renderImageEditSession(activeImageEditSession);
    }

    private void renderImageEditSession(ImageEditSession session) {
        boolean hasImage = session.pendingUri != null || session.imageReference != null;
        session.chooseButton.setText(hasImage ? "替换" : "添加");
        session.deleteButton.setVisibility(hasImage ? View.VISIBLE : View.GONE);
        session.fileName.setText(session.pendingUri != null
                ? "所选相册图片"
                : QuestionImageStore.displayName(session.imageReference));
        session.status.setText(session.pendingUri != null
                ? "保存时自动压缩至最长边1600px"
                : hasImage ? "已保存到题库" : "点击添加图片");
        session.thumbnail.setImageDrawable(null);
        if (!hasImage) {
            session.thumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
            session.thumbnail.setPadding(dp(22), dp(16), dp(22), dp(16));
            return;
        }
        try {
            Bitmap bitmap = decodeImagePreview(session);
            if (bitmap == null) {
                throw new IllegalArgumentException("无法预览图片");
            }
            session.thumbnail.setPadding(0, 0, 0, 0);
            session.thumbnail.setImageBitmap(bitmap);
        } catch (Exception e) {
            session.thumbnail.setImageResource(android.R.drawable.ic_dialog_alert);
            session.status.setText("图片暂时无法预览");
        }
    }

    private Bitmap decodeImagePreview(ImageEditSession session) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream inputStream = openImageForSession(session)) {
            BitmapFactory.decodeStream(inputStream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = QuestionImageStore.calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                480
        );
        try (InputStream inputStream = openImageForSession(session)) {
            return BitmapFactory.decodeStream(inputStream, null, options);
        }
    }

    private InputStream openImageForSession(ImageEditSession session) throws Exception {
        InputStream inputStream = session.pendingUri != null
                ? getContentResolver().openInputStream(session.pendingUri)
                : QuestionImageStore.open(this, questionBankId, session.imageReference);
        if (inputStream == null) {
            throw new IllegalArgumentException("无法读取图片");
        }
        return inputStream;
    }

    private List<Question.Option> parseOptions(String raw) {
        List<Question.Option> options = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('|');
            if (separator <= 0 || separator >= trimmed.length() - 1) {
                throw new IllegalArgumentException("选项格式应为：A | 选项内容");
            }
            String key = trimmed.substring(0, separator).trim();
            String text = trimmed.substring(separator + 1).trim();
            if (!keys.add(key)) {
                throw new IllegalArgumentException("选项 key 重复：" + key);
            }
            options.add(new Question.Option(key, text));
        }
        if (options.isEmpty()) {
            throw new IllegalArgumentException("该题型至少需要一个选项");
        }
        return options;
    }

    private List<String> parseNonEmptyLines(String raw) {
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String value = line.trim();
            if (!value.isEmpty() && seen.add(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private void confirmDeleteQuestion(Question question) {
        if (workingQuestions.size() <= 1) {
            Toast.makeText(this, "题库至少需要保留一道题", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除题目")
                .setMessage("确认删除这道题吗？保存题库后会同步清理它的收藏、错题和未完成进度。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除", (dialog, which) -> {
                    int index = findQuestionIndex(question.getId());
                    if (index >= 0) {
                        workingQuestions.remove(index);
                        deletedQuestionIds.add(question.getId());
                        dirty = true;
                        renderQuestions();
                    }
                })
                .show();
    }

    private void saveQuestionBank() {
        String displayName = nameEdit.getText().toString().trim();
        if (displayName.isEmpty()) {
            nameLayout.setError("请输入题库名称");
            nameEdit.requestFocus();
            return;
        }
        try {
            if (!dirty) {
                QuestionRepository.renameImportedQuestionBank(this, questionBankId, displayName);
                Toast.makeText(this, "题库已保存", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            List<Question> normalized = new ArrayList<>();
            for (int i = 0; i < workingQuestions.size(); i++) {
                Question question = workingQuestions.get(i);
                normalized.add(new Question(
                        question.getId(),
                        i + 1,
                        question.getPaper(),
                        question.getNumberInPaper(),
                        question.getType(),
                        question.getStem(),
                        question.getOptions(),
                        question.getAnswers(),
                        question.getImage(),
                        question.getExplanation(),
                        question.getMatching(),
                        question.getExplanationImages(), question.getSourceChapter(), question.getSourceName()
                ));
            }
            QuestionRepository.saveImportedQuestionBank(this, questionBankId, displayName, normalized);
            ProgressStore progressStore = new ProgressStore(this);
            boolean resetDailyProgress = progressStore.hasDailySessionInProgress(questionBankId);
            progressStore.clearDailySession(questionBankId);
            progressStore.clearTypeSessions(questionBankId);
            for (String questionId : deletedQuestionIds) {
                progressStore.removeQuestionData(questionBankId, questionId);
                QuestionRepository.removeAnswerOverride(this, questionBankId, questionId);
            }
            dirty = false;
            Toast.makeText(
                    this,
                    resetDailyProgress ? "题库已保存，未完成的日常进度已重置" : "题库已保存",
                    Toast.LENGTH_SHORT
            ).show();
            finish();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("保存失败")
                    .setMessage(e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private int findQuestionIndex(String questionId) {
        for (int i = 0; i < workingQuestions.size(); i++) {
            if (workingQuestions.get(i).getId().equals(questionId)) {
                return i;
            }
        }
        return -1;
    }

    private String formatOptions(Question question) {
        StringBuilder builder = new StringBuilder();
        for (Question.Option option : question.getOptions()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(option.getKey()).append(" | ").append(option.getText());
        }
        return builder.toString();
    }

    private int typeIndex(String type) {
        for (int i = 0; i < TYPE_CODES.length; i++) {
            if (TYPE_CODES[i].equals(type)) {
                return i;
            }
        }
        return 0;
    }

    private static String formatMatchingItems(List<MatchingData.Item> items) {
        List<String> lines = new ArrayList<>();
        for (MatchingData.Item item : items) {
            lines.add(item.getId() + " | " + item.getText());
        }
        return TextUtils.join("\n", lines);
    }

    private static String formatMatchingPairs(List<MatchingData.Pair> pairs) {
        List<String> lines = new ArrayList<>();
        for (MatchingData.Pair pair : pairs) {
            lines.add(pair.getLeftId() + " | " + pair.getRightId());
        }
        return TextUtils.join("\n", lines);
    }

    private static List<MatchingData.Item> parseMatchingItems(String raw) {
        List<MatchingData.Item> result = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\s*[|｜]\\s*", 2);
            if (parts.length != 2) throw new IllegalArgumentException("连线项目格式应为 ID | 内容");
            result.add(new MatchingData.Item(parts[0], parts[1]));
        }
        return result;
    }

    private static List<MatchingData.Pair> parseMatchingPairs(String raw) {
        List<MatchingData.Pair> result = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\s*[|｜]\\s*", 2);
            if (parts.length != 2) throw new IllegalArgumentException("标准连线格式应为 左ID | 右ID");
            result.add(new MatchingData.Pair(parts[0], parts[1]));
        }
        return result;
    }

    private EditText field(String hint, String value, boolean multiline) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setTextColor(getColor(R.color.brand_ink));
        editText.setHintTextColor(getColor(R.color.brand_text_muted));
        editText.setSingleLine(!multiline);
        if (multiline) {
            editText.setMinLines(2);
            editText.setMaxLines(6);
            editText.setGravity(Gravity.TOP | Gravity.START);
        }
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        editText.setLayoutParams(params);
        return editText;
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.brand_text_secondary));
        label.setTextSize(14);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        label.setPadding(0, dp(8), 0, dp(4));
        return label;
    }

    private MaterialButton smallButton(String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setTextSize(12);
        button.setMinHeight(dp(36));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private GradientDrawable roundedBackground(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(color));
        background.setCornerRadius(dp(radius));
        return background;
    }

    private TextView chip(String value) {
        TextView chip = new TextView(this);
        chip.setText(value);
        chip.setTextSize(12);
        chip.setTextColor(getColor(R.color.brand_primary_dark));
        chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        chip.setBackground(roundedBackground(R.color.brand_primary_soft, 6));
        return chip;
    }

    private MaterialButton actionButton(String text, int foreground, int background, int icon) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(getColor(foreground));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(background)));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setInsetTop(dp(6));
        button.setInsetBottom(dp(6));
        button.setCornerRadius(dp(8));
        button.setElevation(0);
        button.setStateListAnimator(null);
        if (icon != 0) {
            button.setIconResource(icon);
            button.setIconTint(ColorStateList.valueOf(getColor(foreground)));
            button.setIconSize(dp(18));
            button.setIconPadding(dp(6));
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        }
        return button;
    }

    private void confirmExitIfNeeded() {
        if (!dirty && nameEdit.getText().toString().trim().equals(questionBank.getDisplayName())) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("放弃修改？")
                .setMessage("尚未保存的题库修改会丢失。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("放弃修改", (dialog, which) -> {
                    QuestionImageStore.cleanupOrphans(
                            this,
                            questionBankId,
                            QuestionRepository.load(this, questionBankId)
                    );
                    finish();
                })
                .show();
    }

    private static final class ImageEditSession {
        private MaterialCardView card;
        private ImageView thumbnail;
        private TextView fileName;
        private TextView status;
        private MaterialButton chooseButton;
        private MaterialButton deleteButton;
        private String imageReference;
        private Uri pendingUri;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
