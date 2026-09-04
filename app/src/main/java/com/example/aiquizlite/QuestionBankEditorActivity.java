package com.example.aiquizlite;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class QuestionBankEditorActivity extends AppCompatActivity {
    private static final long DRAG_HOLD_MS = 650L;
    private static final String[] TYPE_CODES = {"single", "multi", "judge", "fill_blank"};
    private static final String[] TYPE_LABELS = {"单选题", "多选题", "判断题", "填空题"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Question> workingQuestions = new ArrayList<>();
    private final Set<String> deletedQuestionIds = new HashSet<>();
    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::handlePickedImage);

    private QuestionRepository.QuestionBank questionBank;
    private String questionBankId;
    private EditText searchEdit;
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
        root.setPadding(dp(20), dp(52), dp(20), dp(20));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        MaterialButton backButton = new MaterialButton(this);
        backButton.setText("返回");
        backButton.setOnClickListener(view -> confirmExitIfNeeded());
        header.addView(backButton, wrapWrap());

        TextView title = new TextView(this);
        title.setText(questionBank.getDisplayName());
        title.setTextColor(getColor(R.color.brand_ink));
        title.setTextSize(21);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        titleParams.leftMargin = dp(10);
        header.addView(title, titleParams);

        MaterialButton saveButton = new MaterialButton(this);
        saveButton.setText("保存题库");
        saveButton.setOnClickListener(view -> saveQuestionBank());
        header.addView(saveButton, wrapWrap());

        TextView hint = new TextView(this);
        hint.setText("搜索、编辑、新增或删除题目；清空搜索后长按题目卡片可拖动排序。修改只会在点击“保存题库”后写入。图片暂不可更换。");
        hint.setTextColor(getColor(R.color.brand_text_secondary));
        hint.setTextSize(13);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(10);
        root.addView(hint, hintParams);

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolsParams = matchWrap();
        toolsParams.topMargin = dp(14);
        root.addView(tools, toolsParams);

        searchEdit = new EditText(this);
        searchEdit.setHint("搜索题干、题号或试卷");
        searchEdit.setSingleLine(true);
        searchEdit.setTextColor(getColor(R.color.brand_ink));
        tools.addView(searchEdit, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderQuestions();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        MaterialButton addButton = new MaterialButton(this);
        addButton.setText("新增");
        addButton.setOnClickListener(view -> showQuestionDialog(null));
        LinearLayout.LayoutParams addParams = wrapWrap();
        addParams.leftMargin = dp(10);
        tools.addView(addButton, addParams);

        listScrollView = new ScrollView(this);
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
        if (listContainer.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("没有匹配的题目");
            empty.setTextColor(getColor(R.color.brand_text_muted));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(40));
            listContainer.addView(empty, matchWrap());
        }
    }

    private boolean matchesQuery(Question question, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return question.getStem().toLowerCase(Locale.ROOT).contains(query)
                || question.getPaper().toLowerCase(Locale.ROOT).contains(query)
                || question.getId().toLowerCase(Locale.ROOT).contains(query)
                || String.valueOf(question.getNumberInPaper()).contains(query);
    }

    private View createQuestionRow(Question question, int workingIndex) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.brand_card));
        card.setRadius(dp(10));
        card.setCardElevation(dp(2));
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        card.addView(row);

        TextView summary = new TextView(this);
        summary.setText(
                (workingIndex + 1) + ". [" + question.typeLabel() + "] " + question.getStem()
                        + "\n" + question.getPaper() + " · 原卷第 " + question.getNumberInPaper() + " 题"
        );
        summary.setTextColor(getColor(R.color.brand_ink));
        summary.setTextSize(14);
        summary.setMaxLines(4);
        row.addView(summary, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        MaterialButton editButton = smallButton("编辑");
        editButton.setOnClickListener(view -> showQuestionDialog(question));
        row.addView(editButton, wrapWrap());

        MaterialButton deleteButton = smallButton("删除");
        deleteButton.setTextColor(getColor(R.color.brand_error));
        deleteButton.setOnClickListener(view -> confirmDeleteQuestion(question));
        row.addView(deleteButton, wrapWrap());

        bindDrag(summary, card, workingIndex);
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
        row.setElevation(dp(2));
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
        content.addView(label("正确答案"), matchWrap());
        EditText answersEdit = field(
                "每行一个；填空题可填写多个可接受答案",
                creating ? "" : String.join("\n", existing.getAnswers()),
                true
        );
        content.addView(answersEdit, matchWrap());
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

        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int visibility = "fill_blank".equals(TYPE_CODES[position]) ? View.GONE : View.VISIBLE;
                optionsTitle.setVisibility(visibility);
                optionsEdit.setVisibility(visibility);
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
                        imageSession.imageReference
                );
                if (imageSession.pendingUri != null) {
                    String importedImage = QuestionImageStore.importFromUri(
                            this,
                            questionBankId,
                            imageSession.pendingUri
                    );
                    updated = copyQuestionWithImage(updated, importedImage);
                }
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
            if (activeImageEditSession == imageSession) {
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
            String imageReference
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
        List<String> answers = parseNonEmptyLines(answersEdit.getText().toString());
        if (answers.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个正确答案");
        }
        List<Question.Option> options = "fill_blank".equals(type)
                ? new ArrayList<>()
                : parseOptions(optionsEdit.getText().toString());
        if (!"fill_blank".equals(type)) {
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
                explanation.isEmpty() ? null : explanation
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
                question.getExplanation()
        );
    }

    private ImageEditSession createImageEditSession(Question existing) {
        ImageEditSession session = new ImageEditSession();
        session.imageReference = existing == null ? null : existing.getImage();

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
                : hasImage ? "已保存到题库" : "每道题最多一张图片");
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
        try {
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
                        question.getExplanation()
                ));
            }
            QuestionRepository.saveImportedQuestionBank(this, questionBankId, normalized);
            ProgressStore progressStore = new ProgressStore(this);
            boolean resetDailyProgress = progressStore.hasDailySessionInProgress(questionBankId);
            progressStore.clearDailySession(questionBankId);
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

    private void confirmExitIfNeeded() {
        if (!dirty) {
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
