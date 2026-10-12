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
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class ImportQuestionBankActivity extends AppCompatActivity {
    private EditText bankNameEdit;
    private EditText jsonEdit;
    private TextView importHintText;
    private final List<android.view.View> importControls = new ArrayList<>();
    private QuestionBankFileImportModel fileImportModel;
    private final ActivityResultLauncher<String[]> pickJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handlePickedJson);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fileImportModel = new ViewModelProvider(this).get(QuestionBankFileImportModel.class);
        buildLayout();
        if (savedInstanceState != null) {
            bankNameEdit.setText(savedInstanceState.getString("bankName", ""));
            jsonEdit.setText(fileImportModel.draftJson);
        }
        setFileImportRunning(fileImportModel.isRunning());
        fileImportModel.result.observe(this, result -> {
            if (result == null) {
                return;
            }
            fileImportModel.consumeResult();
            setFileImportRunning(false);
            if (result.error != null) {
                showFileImportError(result.error);
            } else if (result.bank != null) {
                completeImport(result.bank);
            } else {
                try {
                    fillImportedJson(result.rawJson, result.displayName);
                } catch (Exception e) {
                    showFileImportError(e.getMessage());
                }
            }
        });
        if (savedInstanceState == null) {
            handleExternalImportIntent(getIntent());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("bankName", bankNameEdit.getText().toString());
        fileImportModel.draftJson = jsonEdit.getText().toString();
        super.onSaveInstanceState(outState);
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
        importControls.add(doneButton);
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
        importControls.add(bankNameEdit);

        MaterialButton pickButton = new MaterialButton(this);
        pickButton.setText("选择本地 JSON / ZIP 文件");
        importControls.add(pickButton);
        pickButton.setIconResource(R.drawable.ic_import_question_bank);
        pickButton.setIconPadding(dp(8));
        pickButton.setOnClickListener(view -> pickJsonFile());
        LinearLayout.LayoutParams pickParams = matchWrap();
        pickParams.topMargin = dp(14);
        content.addView(pickButton, pickParams);

        MaterialButton pasteButton = new MaterialButton(this);
        pasteButton.setText("粘贴题库 JSON");
        importControls.add(pasteButton);
        pasteButton.setOnClickListener(view -> pasteQuestionJson());
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
        importHintText.setText("JSON 可编辑后保存；选择 ZIP 后直接导入题库与图片。ZIP 内放一个 JSON 和 image 文件夹，题目的 image 填写 image/图片名.png。");
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
        importControls.add(jsonEdit);
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
        copyButton.setText("复制 JSON 格式提示词");
        copyButton.setOnClickListener(view -> copyPrompt());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        copyParams.topMargin = dp(12);
        content.addView(copyButton, copyParams);
    }

    private void pickJsonFile() {
        pickJsonLauncher.launch(new String[]{"application/json", "application/zip", "application/x-zip-compressed", "text/json", "text/plain", "application/octet-stream"});
    }

    private void handlePickedJson(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            importExternalUri(uri);
        } catch (Exception e) {
            showFileImportError(e.getMessage());
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
                    .setMessage("无法读取题库 JSON / ZIP：" + e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void importExternalUri(Uri uri) throws Exception {
        fileImportModel.importFile(uri, resolveNameFromUri(uri), getContentResolver().getType(uri));
        setFileImportRunning(true);
    }

    private void setFileImportRunning(boolean running) {
        for (android.view.View control : importControls) {
            control.setEnabled(!running);
        }
        if (running) {
            importHintText.setText("正在读取题库并导入图片，请稍候…");
        }
    }

    private void showFileImportError(String message) {
        importHintText.setText("导入失败，请检查 ZIP 内的 JSON 和 image 图片路径后重新选择。");
        new AlertDialog.Builder(this)
                .setTitle("导入失败")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void pasteQuestionJson() {
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
            fillImportedJson(raw, "粘贴导入题库");
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
        String name = QuestionRepository.resolveDisplayNameFromRawJson(raw,
                ExternalImportRules.bankNameFromFile(fallbackName));
        bankNameEdit.setText(name);
        importHintText.setText("已读取 " + questions.length() + " 道题，可在导入框内修改后点击右上角完成。");
    }

    private void finishImport() {
        if (fileImportModel.isRunning()) {
            return;
        }
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
            completeImport(bank);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("保存失败")
                    .setMessage("请检查 JSON 格式：" + e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void completeImport(QuestionRepository.QuestionBank bank) {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(MainActivity.KEY_SELECTED_BANK, bank.getId())
                .apply();
        Toast.makeText(this, "已导入：" + bank.getDisplayName(), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK, new Intent().putExtra(
                DownloadLibraryActivity.EXTRA_IMPORTED_BANK_ID, bank.getId()));
        finish();
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
        if (name == null || name.trim().isEmpty()) {
            name = fallbackName;
        }
        // Keep the full filename until format validation; stripping .txt here would
        // incorrectly make a document look like an extension-less question-bank file.
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
                + "每道题必须包含：id、index、paper、numberInPaper、type、stem、options、answers、explanation、image；可另有 matching、explanationImages。\n"
                + "type 只能是 single、multi、judge、fill_blank、matching。\n"
                + "单选题 answers 只有一个选项 key，多选题 answers 有多个 key，判断题建议用 A/B 表示正确/错误。\n"
                + "填空题只支持单空，type 使用 fill_blank，options 必须是 []，answers 写一个或多个明确可接受的完整答案。不要自行扩展近义词。\n"
                + "连线题 type=matching，options=[]、answers=[]；matching 包含 mode(one_to_one 或 left_reusable)、leftItems/rightItems（各项 id、text）和 correctPairs（leftId、rightId）。每个右侧项恰好对应一个左侧项；左侧可复用时用 left_reusable。不可把连线题转成多选或填空。\n"
                + "explanation 保存答案解析，原文没有解析时必须设为 null；该字段适用于所有题型。\n"
                + "含图片时请将 JSON 和 image 文件夹一起压缩为 ZIP；作答前必须可见的题图放 image，例如 image/q1.png；答案图只放 explanationImages 数组，提交后显示。没有题图填 null，没有答案图填 []。若图片角色或答案无法确定，请标出原题号供人工核对，不得猜测。\n"
                + "清理原文中的题号、[填空题]/(填空题)标签、我的答案、分值、正确答案标题、(1)答案序号和无正文的AI讲解标签。只把正确答案正文写入 answers，把答案解析正文写入 explanation。\n\n"
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
                + "    \"explanation\": null,\n"
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
                + "    \"explanation\": \"示例解析\",\n"
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
                + "    \"explanation\": null,\n"
                + "    \"image\": null\n"
                + "  },\n"
                + "  {\n"
                + "    \"id\": \"q4\",\n"
                + "    \"index\": 4,\n"
                + "    \"paper\": \"示例题库\",\n"
                + "    \"numberInPaper\": 4,\n"
                + "    \"type\": \"fill_blank\",\n"
                + "    \"stem\": \"防火墙上的同一个接口不能同时加入多个不同的_______。\",\n"
                + "    \"options\": [],\n"
                + "    \"answers\": [\"安全区域\"],\n"
                + "    \"explanation\": \"华为 USG 防火墙接口只能加入一个安全区域\",\n"
                + "    \"image\": null\n"
                + "  },\n"
                + "  {\"id\":\"q5\",\"index\":5,\"paper\":\"示例题库\",\"numberInPaper\":5,\"type\":\"matching\",\"stem\":\"连接左右项目\",\"options\":[],\"answers\":[],\"matching\":{\"mode\":\"left_reusable\",\"leftItems\":[{\"id\":\"L1\",\"text\":\"属性 A\"}],\"rightItems\":[{\"id\":\"R1\",\"text\":\"描述一\"},{\"id\":\"R2\",\"text\":\"描述二\"}],\"correctPairs\":[{\"leftId\":\"L1\",\"rightId\":\"R1\"},{\"leftId\":\"L1\",\"rightId\":\"R2\"}]},\"explanation\":null,\"image\":null,\"explanationImages\":[]}\n"
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
