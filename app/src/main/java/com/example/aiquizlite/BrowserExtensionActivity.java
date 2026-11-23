package com.example.aiquizlite;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Introduces the independent desktop extension and its file-based import workflow. */
public class BrowserExtensionActivity extends AppCompatActivity {
    static final String REPOSITORY_URL =
            "https://github.com/zhixiao113/AIQuizLite-browser-extension";
    private static final String RELEASES_URL = REPOSITORY_URL + "/releases/latest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setNavigationBarColor(getColor(R.color.brand_surface));
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(true);
        setContentView(R.layout.activity_browser_extension);

        View root = findViewById(R.id.browserExtensionRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
        findViewById(R.id.browserExtensionBack).setOnClickListener(view -> finish());
        findViewById(R.id.browserExtensionDownload).setOnClickListener(view -> openLink(RELEASES_URL));
        findViewById(R.id.browserExtensionRepository).setOnClickListener(view -> openLink(REPOSITORY_URL));
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, getString(R.string.browser_extension_no_browser, url),
                    Toast.LENGTH_LONG).show();
        }
    }
}
