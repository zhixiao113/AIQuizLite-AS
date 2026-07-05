package com.example.aiquizlite;

import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class ImportGuideActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLayout();
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
        title.setText("如何导入题库");
        title.setTextColor(getColor(R.color.brand_ink));
        title.setTextSize(24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton closeButton = new MaterialButton(this);
        closeButton.setText("完成");
        closeButton.setOnClickListener(view -> finish());
        header.addView(closeButton);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        scrollParams.topMargin = dp(18);
        root.addView(scrollView, scrollParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content);

        addIntro(content);
        addGuideSection(
                content,
                "从微信导入",
                "电脑制作 JSON 文件，发到微信。手机微信点开文件后选择“用其他应用打开”或“分享”，再选择 AIQuizLite。导入页会自动读取并填入 JSON。",
                ImportGuideIllustrationView.MODE_WECHAT_SHARE
        );
        addGuideSection(
                content,
                "粘贴 AI JSON",
                "复制 AI 生成的题库 JSON，回到导入页点击“粘贴 AI JSON”。应用会自动清理代码块，识别题库名并显示题目数量。",
                ImportGuideIllustrationView.MODE_AI_PASTE
        );
        addGuideSection(
                content,
                "选择本地文件",
                "把 .json 文件保存到手机，点击“选择本地 .json 文件”。读取成功后可以先检查内容，再点击右上角“完成”。",
                ImportGuideIllustrationView.MODE_LOCAL_FILE
        );
        addFormatTip(content);
    }

    private void addIntro(LinearLayout content) {
        TextView intro = new TextView(this);
        intro.setText("AIQuizLite 支持三种导入方式。无论从哪里导入，都会先进入导入框预览，确认无误后再保存成题库。");
        intro.setTextColor(getColor(R.color.brand_text_secondary));
        intro.setTextSize(15);
        intro.setLineSpacing(dp(3), 1f);
        content.addView(intro, matchWrap());
    }

    private void addGuideSection(LinearLayout content, String title, String body, int illustrationMode) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.brand_ink));
        titleView.setTextSize(18);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(24);
        content.addView(titleView, titleParams);

        ImportGuideIllustrationView illustrationView = new ImportGuideIllustrationView(this);
        illustrationView.setMode(illustrationMode);
        illustrationView.setBackgroundResource(R.drawable.card_background);
        LinearLayout.LayoutParams imageParams = matchWrap();
        imageParams.topMargin = dp(10);
        content.addView(illustrationView, imageParams);

        TextView bodyView = new TextView(this);
        bodyView.setText(body);
        bodyView.setTextColor(getColor(R.color.brand_text_secondary));
        bodyView.setTextSize(14);
        bodyView.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(10);
        content.addView(bodyView, bodyParams);
    }

    private void addFormatTip(LinearLayout content) {
        TextView tip = new TextView(this);
        tip.setText("格式提示：JSON 可以是题目数组，也可以是 {\"name\":\"题库名\",\"questions\":[...]}。每道题至少需要题干 stem、选项 options 和答案 answers。");
        tip.setTextColor(getColor(R.color.brand_text_secondary));
        tip.setTextSize(13);
        tip.setLineSpacing(dp(3), 1f);
        tip.setPadding(dp(12), dp(12), dp(12), dp(12));
        tip.setBackgroundResource(R.drawable.card_background);
        LinearLayout.LayoutParams tipParams = matchWrap();
        tipParams.topMargin = dp(24);
        content.addView(tip, tipParams);
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
