package com.example.aiquizlite;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ImportQuestionBankActivity extends AppCompatActivity {
    private EditText bankNameEdit;
    private EditText jsonEdit;
    private TextView importHintText;
    private final ActivityResultLauncher<String[]> pickJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handlePickedJson);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLayout();
        if (savedInstanceState == null) {
            handleExternalImportIntent(getIntent());
        }
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(56), dp(24), dp(20));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("导入题库");
        title.setTextColor(getColor(R.color.brand_ink));
        title.setTextSize(24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton doneButton = new MaterialButton(this);
        doneButton.setText("完成");
        doneButton.setOnClickListener(view -> finishImport());
        header.addView(doneButton);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        scrollParams.topMargin = dp(16);
        root.addView(scrollView, scrollParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content);

        bankNameEdit = new EditText(this);
        bankNameEdit.setHint("题库名称");
        bankNameEdit.setSingleLine(true);
        bankNameEdit.setTextColor(getColor(R.color.brand_ink));
        content.addView(bankNameEdit, matchWrap());

        MaterialButton pickButton = new MaterialButton(this);
        pickButton.setText("选择本地 .json 文件");
        pickButton.setIconResource(R.drawable.ic_import_question_bank);
        pickButton.setIconPadding(dp(8));
        pickButton.setOnClickListener(view -> pickJsonFile());
        LinearLayout.LayoutParams pickParams = matchWrap();
        pickParams.topMargin = dp(14);
        content.addView(pickButton, pickParams);

        MaterialButton pasteButton = new MaterialButton(this);
        pasteButton.setText("粘贴 AI JSON");
        pasteButton.setOnClickListener(view -> pasteAiJson());
        LinearLayout.LayoutParams pasteParams = matchWrap();
        pasteParams.topMargin = dp(10);
        content.addView(pasteButton, pasteParams);

        LinearLayout importGuideRow = new LinearLayout(this);
        importGuideRow.setOrientation(LinearLayout.HORIZONTAL);
        importGuideRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams guideRowParams = matchWrap();
        guideRowParams.topMargin = dp(10);
        content.addView(importGuideRow, guideRowParams);

        importHintText = new TextView(this);
        importHintText.setText("导入说明：支持本地文件、AI JSON、微信分享导入");
        importHintText.setTextColor(getColor(R.color.brand_text_secondary));
        importHintText.setTextSize(14);
        importGuideRow.addView(importHintText, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView guideLink = new TextView(this);
        guideLink.setText("查看教程");
        guideLink.setTextColor(0xFF2563EB);
        guideLink.setTextSize(14);
        guideLink.setPadding(dp(12), dp(8), 0, dp(8));
        guideLink.setOnClickListener(view -> startActivity(new Intent(this, ImportGuideActivity.class)));
        importGuideRow.addView(guideLink, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        jsonEdit = new EditText(this);
        jsonEdit.setHint("导入框：题目 JSON 会显示在这里");
        jsonEdit.setMinLines(12);
        jsonEdit.setGravity(Gravity.TOP | Gravity.START);
        jsonEdit.setTextSize(13);
        jsonEdit.setHorizontallyScrolling(false);
        jsonEdit.setTextColor(getColor(R.color.brand_ink));
        LinearLayout.LayoutParams jsonParams = matchWrap();
        jsonParams.topMargin = dp(12);
        content.addView(jsonEdit, jsonParams);

        TextView formatTitle = new TextView(this);
        formatTitle.setText("JSON 格式要求");
        formatTitle.setTextColor(getColor(R.color.brand_ink));
        formatTitle.setTextSize(18);
        formatTitle.setTypeface(formatTitle.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(22);
        content.addView(formatTitle, titleParams);

        TextView promptText = new TextView(this);
        promptText.setText(buildFormatPrompt());
        promptText.setTextColor(getColor(R.color.brand_text_secondary));
        promptText.setTextSize(13);
        promptText.setPadding(dp(12), dp(12), dp(12), dp(12));
        promptText.setBackgroundResource(R.drawable.card_background);
        LinearLayout.LayoutParams promptParams = matchWrap();
        promptParams.topMargin = dp(10);
        content.addView(promptText, promptParams);

        MaterialButton copyButton = new MaterialButton(this);
        copyButton.setText("复制提示词");
        copyButton.setOnClickListener(view -> copyPrompt());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        copyParams.topMargin = dp(12);
        content.addView(copyButton, copyParams);
    }

    private void pickJsonFile() {
        pickJsonLauncher.launch(new String[]{"application/json", "text/json", "text/plain", "*/*"});
    }

    private void handlePickedJson(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            String raw = readUri(uri);
            fillImportedJson(raw, resolveNameFromUri(uri));
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("导入失败")
                    .setMessage("文件不是可识别的题库 JSON：" + e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void handleExternalImportIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        try {
            if (Intent.ACTION_VIEW.equals(action)) {
                Uri uri = intent.getData();
                if (uri != null) {
                    importExternalUri(uri);
                }
                return;
            }

            if (Intent.ACTION_SEND.equals(action)) {
                Uri streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (streamUri != null) {
                    importExternalUri(streamUri);
                    return;
                }

                ClipData clipData = intent.getClipData();
                if (clipData != null && clipData.getItemCount() > 0 && clipData.getItemAt(0).getUri() != null) {
                    importExternalUri(clipData.getItemAt(0).getUri());
                    return;
                }

                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null && !sharedText.trim().isEmpty()) {
                    fillImportedJson(sharedText, "微信导入题库");
                }
            }
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("微信导入失败")
                    .setMessage("微信文件不是可识别的题库 JSON：" + e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void importExternalUri(Uri uri) throws Exception {
        String raw = readUri(uri);
        fillImportedJson(raw, resolveNameFromUri(uri, "微信导入题库"));
    }

    private void pasteAiJson() {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
            Toast.makeText(this, "剪贴板里没有可粘贴的内容", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipData clipData = clipboardManager.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            Toast.makeText(this, "剪贴板里没有可粘贴的内容", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence text = clipData.getItemAt(0).coerceToText(this);
        String raw = text == null ? "" : text.toString();
        if (raw.trim().isEmpty()) {
            Toast.makeText(this, "剪贴板内容为空", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            fillImportedJson(raw, "AI 生成题库");
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("粘贴失败")
                    .setMessage("剪贴板内容不是可识别的题库 JSON：" + e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void fillImportedJson(String raw, String fallbackName) throws Exception {
        JSONArray questions = QuestionRepository.normalizeImportedJson(raw);
        jsonEdit.setText(questions.toString(2));
        String name = QuestionRepository.resolveDisplayNameFromRawJson(raw, fallbackName);
        bankNameEdit.setText(name);
        importHintText.setText("已读取 " + questions.length() + " 道题，可在导入框内修改后点击右上角完成。");
    }

    private void finishImport() {
        String raw = jsonEdit.getText().toString();
        if (raw.trim().isEmpty()) {
            Toast.makeText(this, "请先选择或粘贴题库 JSON", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONArray questions = QuestionRepository.normalizeImportedJson(raw);
            QuestionRepository.QuestionBank bank = QuestionRepository.importQuestionBank(
                    this,
                    bankNameEdit.getText().toString(),
                    questions
            );
            getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(MainActivity.KEY_SELECTED_BANK, bank.getId())
                    .apply();
            Toast.makeText(this, "已导入：" + bank.getDisplayName(), Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("保存失败")
                    .setMessage("请检查 JSON 格式：" + e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private String readUri(Uri uri) throws Exception {
        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (inputStream == null) {
                throw new IllegalArgumentException("无法读取文件");
            }
            byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String resolveNameFromUri(Uri uri) {
        return resolveNameFromUri(uri, "自定义题库");
    }

    private String resolveNameFromUri(Uri uri, String fallbackName) {
        String name = fallbackName;
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        if (name.endsWith(".txt")) {
            name = name.substring(0, name.length() - ".txt".length());
        }
        return name;
    }

    private void copyPrompt() {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboardManager.setPrimaryClip(ClipData.newPlainText("题库 JSON 生成提示词", buildFormatPrompt()));
        Toast.makeText(this, "已复制提示词", Toast.LENGTH_SHORT).show();
    }

    private String buildFormatPrompt() {
        return "请根据我提供的文档生成题库 JSON。要求只输出 JSON，不要 Markdown。\n"
                + "根节点可以是数组，也可以是 {\"name\":\"题库名\",\"questions\":[...]}。\n"
                + "每道题必须包含：id、index、paper、numberInPaper、type、stem、options、answers、image。\n"
                + "type 只能是 single、multi、judge。\n"
                + "单选题 answers 只有一个选项 key，多选题 answers 有多个 key，判断题建议用 A/B 表示正确/错误。\n\n"
                + "示例：\n"
                + "[\n"
                + "  {\n"
                + "    \"id\": \"q1\",\n"
                + "    \"index\": 1,\n"
                + "    \"paper\": \"示例题库\",\n"
                + "    \"numberInPaper\": 1,\n"
                + "    \"type\": \"single\",\n"
                + "    \"stem\": \"单选题题干\",\n"
                + "    \"options\": [{\"key\":\"A\",\"text\":\"选项A\"},{\"key\":\"B\",\"text\":\"选项B\"}],\n"
                + "    \"answers\": [\"A\"],\n"
                + "    \"image\": null\n"
                + "  },\n"
                + "  {\n"
                + "    \"id\": \"q2\",\n"
                + "    \"index\": 2,\n"
                + "    \"paper\": \"示例题库\",\n"
                + "    \"numberInPaper\": 2,\n"
                + "    \"type\": \"multi\",\n"
                + "    \"stem\": \"多选题题干\",\n"
                + "    \"options\": [{\"key\":\"A\",\"text\":\"选项A\"},{\"key\":\"B\",\"text\":\"选项B\"},{\"key\":\"C\",\"text\":\"选项C\"}],\n"
                + "    \"answers\": [\"A\", \"C\"],\n"
                + "    \"image\": null\n"
                + "  },\n"
                + "  {\n"
                + "    \"id\": \"q3\",\n"
                + "    \"index\": 3,\n"
                + "    \"paper\": \"示例题库\",\n"
                + "    \"numberInPaper\": 3,\n"
                + "    \"type\": \"judge\",\n"
                + "    \"stem\": \"判断题题干\",\n"
                + "    \"options\": [{\"key\":\"A\",\"text\":\"正确\"},{\"key\":\"B\",\"text\":\"错误\"}],\n"
                + "    \"answers\": [\"A\"],\n"
                + "    \"image\": null\n"
                + "  }\n"
                + "]";
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
