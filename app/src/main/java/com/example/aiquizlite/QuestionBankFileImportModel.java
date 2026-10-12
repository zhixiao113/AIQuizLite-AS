package com.example.aiquizlite;

import android.app.Application;
import android.net.Uri;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps a single file import running across Activity recreation. */
public class QuestionBankFileImportModel extends AndroidViewModel {
    final MutableLiveData<Result> result = new MutableLiveData<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean running;
    String draftJson = "";

    public QuestionBankFileImportModel(Application application) {
        super(application);
    }

    boolean isRunning() {
        return running;
    }

    void consumeResult() {
        running = false;
        result.setValue(null);
    }

    void importFile(Uri uri, String displayName, String mimeType) {
        if (running) {
            return;
        }
        running = true;
        executor.execute(() -> {
            Result value = new Result();
            value.displayName = displayName;
            try {
                ExternalImportRules.Destination format = ExternalImportRules.resolve(
                        mimeType, displayName, true, null);
                if (format == ExternalImportRules.Destination.UNSUPPORTED) {
                    throw new IllegalArgumentException("仅支持已有 JSON / ZIP 题库，不支持 TXT、PDF 或 Word 资料转题");
                }
                try (InputStream source = getApplication().getContentResolver().openInputStream(uri)) {
                    if (source == null) {
                        throw new IllegalArgumentException("无法读取文件");
                    }
                    try (BufferedInputStream input = new BufferedInputStream(source)) {
                        input.mark(4);
                        int first = input.read();
                        int second = input.read();
                        input.reset();
                        if (ExternalImportRules.shouldReadZip(format, first, second)) {
                            value.bank = QuestionBankPackageManager.importPackage(getApplication(), input);
                        } else {
                            ByteArrayOutputStream output = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                if (output.size() + count > 4 * 1024 * 1024) {
                                    throw new IllegalArgumentException("题库 JSON 不能超过 4MB");
                                }
                                output.write(buffer, 0, count);
                            }
                            value.rawJson = output.toString(StandardCharsets.UTF_8.name());
                        }
                    }
                }
            } catch (Exception error) {
                value.error = error.getMessage() == null ? "无法读取题库文件" : error.getMessage();
            }
            result.postValue(value);
        });
    }

    @Override
    protected void onCleared() {
        executor.shutdown();
        super.onCleared();
    }

    static final class Result {
        String displayName;
        String rawJson;
        String error;
        QuestionRepository.QuestionBank bank;
    }
}
