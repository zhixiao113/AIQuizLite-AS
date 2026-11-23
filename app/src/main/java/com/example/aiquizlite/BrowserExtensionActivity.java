package com.example.aiquizlite;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/**
 * Explains the standalone browser extension and links to its GitHub repository.
 * The extension itself is not bundled with the app: it runs inside Chrome/Edge and
 * only produces JSON/ZIP banks that the app imports through the existing entry.
 */
public class BrowserExtensionActivity extends AppCompatActivity {
    static final String REPOSITORY_URL =
            "https://github.com/zhixiao113/AIQuizLite-browser-extension";

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
        title.setText("浏览器插件");
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

        addParagraph(content,
                "浏览器插件是独立的 Chrome / Edge 侧边栏扩展，负责把题库网页里的题目整理成本应用可导入的 JSON / ZIP 题库。"
                        + "它运行在浏览器里，不在应用内执行，也不改变应用现有的导入方式和学习数据。",
                0);

        addSectionTitle(content, "它能做什么");
        addBullet(content, "整库采集：读取题库页面数据、内嵌 JSON，或按答题卡逐题读取，并校验原题号覆盖。");
        addBullet(content, "AI 整理与核对：把网页原文转成题库字段；缺标准答案、缺图或答案冲突时保留待核对，不猜答案。");
        addBullet(content, "导出：无图输出 JSON；有图输出 ZIP（question-bank.json + images/），下载后自行传到手机。");
        addBullet(content, "题型：单选、多选、判断、单空填空和连线题。");

        addSectionTitle(content, "使用方法");
        addBullet(content, "1. 在 Edge 或 Chrome 的扩展管理页开启开发者模式，加载插件目录中的 dist 文件夹。");
        addBullet(content, "2. 打开已登录的题库网页，点击工具栏图标或按 Alt+Shift+Q 打开侧边栏。");
        addBullet(content, "3. 在插件设置里填写模型地址、模型和密钥，然后发送“把这份题库导出来，共 N 题”。");
        addBullet(content, "4. 下载题库文件后传到手机，回到本应用用“＋ 导入题库”选择文件导入。");

        addSectionTitle(content, "代码仓库");
        addParagraph(content,
                "插件独立发布，源码和安装说明都在下面的仓库里。版本更新、问题反馈也在这里。",
                dp(8));
        addRepositoryCard(content);

        addSectionTitle(content, "边界说明");
        addParagraph(content,
                "插件暂不提供扫码或局域网直传，题库通过文件方式传到手机。"
                        + "插件使用浏览器现有的题库网页登录状态，不读取手机上的题库、错题和学习记录；"
                        + "本应用也不会读取浏览器的任何数据。",
                dp(6));
    }

    private void addRepositoryCard(LinearLayout content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_background);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(12);
        content.addView(card, cardParams);

        TextView repoName = new TextView(this);
        repoName.setText("zhixiao113/AIQuizLite-browser-extension");
        repoName.setTextColor(getColor(R.color.brand_ink));
        repoName.setTextSize(15);
        repoName.setTypeface(repoName.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(repoName, matchWrap());

        TextView repoUrl = new TextView(this);
        repoUrl.setText(REPOSITORY_URL);
        repoUrl.setTextColor(getColor(R.color.brand_primary));
        repoUrl.setTextSize(13);
        repoUrl.setOnClickListener(view -> openRepository());
        LinearLayout.LayoutParams urlParams = matchWrap();
        urlParams.topMargin = dp(6);
        card.addView(repoUrl, urlParams);

        MaterialButton openButton = new MaterialButton(this);
        openButton.setText("打开 GitHub 仓库");
        openButton.setOnClickListener(view -> openRepository());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(14);
        card.addView(openButton, buttonParams);
    }

    private void openRepository() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "没有可打开链接的浏览器，请手动访问 " + REPOSITORY_URL,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addSectionTitle(LinearLayout content, String text) {
        TextView titleView = new TextView(this);
        titleView.setText(text);
        titleView.setTextColor(getColor(R.color.brand_ink));
        titleView.setTextSize(18);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(24);
        content.addView(titleView, params);
    }

    private void addParagraph(LinearLayout content, String text, int extraTopMargin) {
        TextView paragraph = new TextView(this);
        paragraph.setText(text);
        paragraph.setTextColor(getColor(R.color.brand_text_secondary));
        paragraph.setTextSize(14);
        paragraph.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = extraTopMargin + dp(10);
        content.addView(paragraph, params);
    }

    private void addBullet(LinearLayout content, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(10);
        content.addView(row, rowParams);

        TextView dot = new TextView(this);
        dot.setText("•");
        dot.setTextColor(getColor(R.color.brand_primary));
        dot.setTextSize(14);
        row.addView(dot);

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(getColor(R.color.brand_text_secondary));
        body.setTextSize(14);
        body.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bodyParams.leftMargin = dp(8);
        row.addView(body, bodyParams);
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
