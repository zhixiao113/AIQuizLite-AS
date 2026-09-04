package com.example.aiquizlite;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

public class ExternalImportActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        route(getIntent());
    }

    private void route(Intent source) {
        if (source == null) {
            finish();
            return;
        }

        Uri uri = findSharedUri(source);
        CharSequence sharedText = source.getCharSequenceExtra(Intent.EXTRA_TEXT);
        ExternalImportRules.Destination destination = ExternalImportRules.resolve(
                source.getType(),
                resolveDisplayName(uri),
                uri != null,
                sharedText
        );

        if (destination == ExternalImportRules.Destination.LEGACY_WORD_UNSUPPORTED) {
            Toast.makeText(this, "旧版 .doc 暂不支持，请另存为 .docx 后再导入", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (destination == ExternalImportRules.Destination.UNSUPPORTED) {
            Toast.makeText(this, "无法识别该文件，仅支持 JSON、TXT、文字版 PDF 和 DOCX", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Class<?> targetClass = destination == ExternalImportRules.Destination.QUESTION_BANK_JSON
                ? ImportQuestionBankActivity.class
                : MaterialToQuizActivity.class;
        Intent target = copyForTarget(source, targetClass);
        try {
            startActivity(target);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件：" + safeMessage(e), Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private Intent copyForTarget(Intent source, Class<?> targetClass) {
        Intent target = new Intent(this, targetClass);
        target.setAction(source.getAction());
        if (source.getData() != null) {
            target.setDataAndType(source.getData(), source.getType());
        } else {
            target.setType(source.getType());
        }
        if (source.getExtras() != null) {
            target.putExtras(source.getExtras());
        }
        if (source.getClipData() != null) {
            target.setClipData(source.getClipData());
        }
        int permissionFlags = source.getFlags() & (
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        target.addFlags(permissionFlags);
        return target;
    }

    static Uri findSharedUri(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData();
        }
        Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream instanceof Uri) {
            return (Uri) stream;
        }
        ClipData clipData = intent.getClipData();
        if (clipData != null && clipData.getItemCount() > 0) {
            return clipData.getItemAt(0).getUri();
        }
        return intent.getData();
    }

    private String resolveDisplayName(Uri uri) {
        if (uri == null) {
            return "";
        }
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    return name == null ? "" : name;
                }
            }
        } catch (Exception ignored) {
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "" : segment;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "系统未提供具体原因" : message;
    }
}
