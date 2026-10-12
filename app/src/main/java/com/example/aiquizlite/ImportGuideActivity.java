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
                "把已有 JSON 或 ZIP 题库发到微信，点开后选择“用其他应用打开”或“分享”，再选择 AIQuizLite。JSON 可检查后保存，ZIP 会直接导入题库与图片。",
                ImportGuideIllustrationView.MODE_WECHAT_SHARE
        );
        addGuideSection(
                content,
                "粘贴题库 JSON",
                "复制在外部整理好的题库 JSON，点击“粘贴题库 JSON”。应用会清理代码块、识别题库名并显示题目数量；确认后点击“完成”。此过程不调用 AI。",
                ImportGuideIllustrationView.MODE_JSON_PASTE
        );
        addGuideSection(
                content,
                "选择本地文件",
                "把 .json 或 .zip 题库保存到手机，在总题库点击“＋ 导入题库”，再选择本地 JSON / ZIP 文件。JSON 可检查后保存；ZIP 直接导入。下载库里的 ZIP 也可通过“下载库导入”使用。",
                ImportGuideIllustrationView.MODE_LOCAL_FILE
        );
        addFormatTip(content);
    }

    private void addIntro(LinearLayout content) {
        TextView intro = new TextView(this);
        intro.setText("只导入已有 JSON / ZIP 题库，不支持 TXT、PDF 或 Word 资料转题，也不消耗 AI Token。JSON 可预览后保存，ZIP 直接导入题库和图片。");
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
        tip.setText("格式提示：JSON 可以是题目数组，也可以是 {\"name\":\"题库名\",\"questions\":[...]}。选择/判断/填空题使用 options 和 answers；连线题使用 type=matching、matching.leftItems/rightItems/correctPairs，options=[]、answers=[]。image 是作答前题图；explanationImages 是提交后解析图。缺少解析时 explanation=null。");
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
