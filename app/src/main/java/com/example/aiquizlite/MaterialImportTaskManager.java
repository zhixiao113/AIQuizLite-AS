package com.example.aiquizlite;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MaterialImportTaskManager {
    public enum TaskKind {
        ANALYZE_DOCUMENT,
        LEARNING_PLAN,
        QUESTION_BANK_CONVERSION
    }

    public enum Status {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public interface Listener {
        void onTaskChanged(Snapshot snapshot);
    }

    interface ProgressSink {
        void update(int completed, int total, String message);
    }

    interface TaskWork {
        TaskResult run(ProgressSink progress) throws Exception;
    }

    private static volatile MaterialImportTaskManager instance;

    private final ExecutorService worker;
    private final Executor callbackExecutor;
    private long nextTaskId = 1L;
    private Snapshot snapshot = Snapshot.idle();
    private Listener listener;
    private TaskDefinition retryDefinition;

    private MaterialImportTaskManager() {
        this(
                Executors.newSingleThreadExecutor(),
                command -> new Handler(Looper.getMainLooper()).post(command)
        );
    }

    MaterialImportTaskManager(ExecutorService worker, Executor callbackExecutor) {
        this.worker = worker;
        this.callbackExecutor = callbackExecutor;
    }

    public static MaterialImportTaskManager getInstance() {
        if (instance == null) {
            synchronized (MaterialImportTaskManager.class) {
                if (instance == null) {
                    instance = new MaterialImportTaskManager();
                }
            }
        }
        return instance;
    }

    public synchronized boolean hasPendingTask() {
        return snapshot.status != Status.IDLE;
    }

    public void setListener(Listener listener) {
        Snapshot current;
        synchronized (this) {
            this.listener = listener;
            current = snapshot;
        }
        dispatch(listener, current);
    }

    public synchronized void clearListener(Listener listener) {
        if (this.listener == listener) {
            this.listener = null;
        }
    }

    public synchronized Snapshot getSnapshot() {
        return snapshot;
    }

    public void startDocumentAnalysis(Context context, Uri uri) {
        Context appContext = context.getApplicationContext();
        start(
                TaskKind.ANALYZE_DOCUMENT,
                "正在读取并分析资料",
                "正在安全读取文件内容",
                3,
                progress -> {
                    MaterialDocumentExtractor.DocumentData document =
                            new MaterialDocumentExtractor().extract(appContext, uri);
                    progress.update(1, 3, "文字提取完成，正在整理章节");
                    List<MaterialTextRules.Chapter> chapters =
                            MaterialTextRules.detectChapters(document.getText());
                    if (MaterialTextRules.countCharacters(chapters)
                            > MaterialTextRules.MAX_SELECTED_CHARACTERS) {
                        throw new IllegalArgumentException(
                                "资料文字超过 10 万字，请拆分文件后再导入，避免产生不可控的 Token 消耗"
                        );
                    }
                    progress.update(2, 3, "正在由 AI 判断资料类型");
                    MaterialDocumentAnalysis analysis = new MaterialQuestionGenerator().analyzeDocument(
                            requireCurrentConfig(appContext),
                            document.getDisplayName(),
                            chapters
                    );
                    return TaskResult.analysis(document, chapters, analysis);
                }
        );
    }

    public void startLearningPlan(
            Context context,
            String bankName,
            MaterialDocumentExtractor.DocumentData document,
            List<MaterialTextRules.Chapter> chapters
    ) {
        Context appContext = context.getApplicationContext();
        int requests = MaterialQuestionGenerator.estimateLearningPlanRequests(chapters);
        start(
                TaskKind.LEARNING_PLAN,
                "AI 正在盘点知识点",
                "正在准备分段分析",
                requests,
                progress -> {
                    MaterialGenerationPlan plan = new MaterialQuestionGenerator().recommendLearningPlan(
                            requireCurrentConfig(appContext),
                            bankName,
                            document.getDisplayName(),
                            chapters,
                            progress::update
                    );
                    return TaskResult.learning(document, chapters, plan);
                }
        );
    }

    public void startQuestionBankConversion(
            Context context,
            String bankName,
            MaterialDocumentExtractor.DocumentData document,
            List<MaterialTextRules.Chapter> chapters
    ) {
        Context appContext = context.getApplicationContext();
        int requests = MaterialQuestionGenerator.estimateQuestionBankRequests(chapters);
        start(
                TaskKind.QUESTION_BANK_CONVERSION,
                "AI 正在识别题库",
                "正在准备分段转换",
                requests,
                progress -> {
                    MaterialQuestionGenerator.QuestionBankConversionResult conversion =
                            new MaterialQuestionGenerator().convertQuestionBank(
                                    requireCurrentConfig(appContext),
                                    bankName,
                                    chapters,
                                    progress::update
                            );
                    return TaskResult.conversion(document, chapters, conversion);
                }
        );
    }

    private static ApiConfig requireCurrentConfig(Context context) throws Exception {
        ApiConfig config = new ApiConfigStore(context).getConfig();
        if (!config.isComplete()) {
            throw new OpenAiCompatibleClient.AiClientException(
                    OpenAiCompatibleClient.FailureKind.CONFIG,
                    "AI配置不完整，请检查API地址、API Key和模型名称"
            );
        }
        return config;
    }

    public void retry() {
        TaskDefinition definition;
        synchronized (this) {
            definition = retryDefinition;
        }
        if (definition != null) {
            start(definition.kind, definition.title, definition.message, definition.total, definition.work);
        }
    }

    public synchronized TaskResult consumeResult(long taskId) {
        if (snapshot.taskId != taskId || snapshot.status != Status.SUCCEEDED) {
            return null;
        }
        TaskResult result = snapshot.result;
        snapshot = Snapshot.idle();
        retryDefinition = null;
        return result;
    }

    public synchronized void dismissFinishedTask() {
        if (snapshot.status == Status.RUNNING) {
            return;
        }
        snapshot = Snapshot.idle();
        retryDefinition = null;
    }

    void start(
            TaskKind kind,
            String title,
            String message,
            int total,
            TaskWork work
    ) {
        final long taskId;
        final Snapshot initial;
        synchronized (this) {
            if (snapshot.status == Status.RUNNING) {
                return;
            }
            taskId = nextTaskId++;
            retryDefinition = new TaskDefinition(kind, title, message, Math.max(1, total), work);
            initial = Snapshot.running(taskId, kind, title, message, 0, Math.max(1, total));
            snapshot = initial;
        }
        notifyListener(initial);
        worker.execute(() -> {
            try {
                TaskResult result = work.run((completed, progressTotal, progressMessage) ->
                        updateProgress(taskId, completed, progressTotal, progressMessage));
                complete(taskId, result);
            } catch (Exception error) {
                fail(taskId, error);
            }
        });
    }

    private void updateProgress(long taskId, int completed, int total, String message) {
        Snapshot update;
        synchronized (this) {
            if (snapshot.taskId != taskId || snapshot.status != Status.RUNNING) {
                return;
            }
            update = Snapshot.running(
                    taskId,
                    snapshot.kind,
                    snapshot.title,
                    message,
                    Math.max(0, completed),
                    Math.max(1, total)
            );
            snapshot = update;
        }
        notifyListener(update);
    }

    private void complete(long taskId, TaskResult result) {
        Snapshot update;
        synchronized (this) {
            if (snapshot.taskId != taskId || snapshot.status != Status.RUNNING) {
                return;
            }
            update = Snapshot.succeeded(snapshot, result);
            snapshot = update;
        }
        notifyListener(update);
    }

    private void fail(long taskId, Exception error) {
        Snapshot update;
        synchronized (this) {
            if (snapshot.taskId != taskId || snapshot.status != Status.RUNNING) {
                return;
            }
            update = Snapshot.failed(snapshot, AiFailureInfo.from(error));
            snapshot = update;
        }
        notifyListener(update);
    }

    private void notifyListener(Snapshot update) {
        Listener target;
        synchronized (this) {
            target = listener;
        }
        dispatch(target, update);
    }

    private void dispatch(Listener target, Snapshot update) {
        if (target != null) {
            callbackExecutor.execute(() -> target.onTaskChanged(update));
        }
    }

    void shutdownForTests() {
        worker.shutdownNow();
    }

    public static final class Snapshot {
        private final long taskId;
        private final TaskKind kind;
        private final Status status;
        private final String title;
        private final String message;
        private final int completed;
        private final int total;
        private final TaskResult result;
        private final AiFailureInfo failure;

        private Snapshot(
                long taskId,
                TaskKind kind,
                Status status,
                String title,
                String message,
                int completed,
                int total,
                TaskResult result,
                AiFailureInfo failure
        ) {
            this.taskId = taskId;
            this.kind = kind;
            this.status = status;
            this.title = title;
            this.message = message;
            this.completed = completed;
            this.total = total;
            this.result = result;
            this.failure = failure;
        }

        static Snapshot idle() {
            return new Snapshot(0L, null, Status.IDLE, "", "", 0, 1, null, null);
        }

        static Snapshot running(
                long taskId,
                TaskKind kind,
                String title,
                String message,
                int completed,
                int total
        ) {
            return new Snapshot(
                    taskId, kind, Status.RUNNING, title, message,
                    completed, total, null, null
            );
        }

        static Snapshot succeeded(Snapshot source, TaskResult result) {
            return new Snapshot(
                    source.taskId, source.kind, Status.SUCCEEDED,
                    source.title, "处理完成", source.total, source.total, result, null
            );
        }

        static Snapshot failed(Snapshot source, AiFailureInfo failure) {
            return new Snapshot(
                    source.taskId, source.kind, Status.FAILED,
                    source.title, source.message, source.completed, source.total, null, failure
            );
        }

        public long getTaskId() {
            return taskId;
        }

        public TaskKind getKind() {
            return kind;
        }

        public Status getStatus() {
            return status;
        }

        public String getTitle() {
            return title;
        }

        public String getMessage() {
            return message;
        }

        public int getCompleted() {
            return completed;
        }

        public int getTotal() {
            return total;
        }

        public AiFailureInfo getFailure() {
            return failure;
        }
    }

    public static final class TaskResult {
        private final MaterialDocumentExtractor.DocumentData document;
        private final List<MaterialTextRules.Chapter> chapters;
        private final MaterialDocumentAnalysis analysis;
        private final MaterialGenerationPlan plan;
        private final MaterialQuestionGenerator.QuestionBankConversionResult conversion;

        private TaskResult(
                MaterialDocumentExtractor.DocumentData document,
                List<MaterialTextRules.Chapter> chapters,
                MaterialDocumentAnalysis analysis,
                MaterialGenerationPlan plan,
                MaterialQuestionGenerator.QuestionBankConversionResult conversion
        ) {
            this.document = document;
            this.chapters = chapters == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(chapters));
            this.analysis = analysis;
            this.plan = plan;
            this.conversion = conversion;
        }

        static TaskResult analysis(
                MaterialDocumentExtractor.DocumentData document,
                List<MaterialTextRules.Chapter> chapters,
                MaterialDocumentAnalysis analysis
        ) {
            return new TaskResult(document, chapters, analysis, null, null);
        }

        static TaskResult learning(
                MaterialDocumentExtractor.DocumentData document,
                List<MaterialTextRules.Chapter> chapters,
                MaterialGenerationPlan plan
        ) {
            return new TaskResult(document, chapters, null, plan, null);
        }

        static TaskResult conversion(
                MaterialDocumentExtractor.DocumentData document,
                List<MaterialTextRules.Chapter> chapters,
                MaterialQuestionGenerator.QuestionBankConversionResult conversion
        ) {
            return new TaskResult(document, chapters, null, null, conversion);
        }

        static TaskResult emptyForTests() {
            return new TaskResult(null, null, null, null, null);
        }

        public MaterialDocumentExtractor.DocumentData getDocument() {
            return document;
        }

        public List<MaterialTextRules.Chapter> getChapters() {
            return chapters;
        }

        public MaterialDocumentAnalysis getAnalysis() {
            return analysis;
        }

        public MaterialGenerationPlan getPlan() {
            return plan;
        }

        public MaterialQuestionGenerator.QuestionBankConversionResult getConversion() {
            return conversion;
        }
    }

    private static final class TaskDefinition {
        final TaskKind kind;
        final String title;
        final String message;
        final int total;
        final TaskWork work;

        TaskDefinition(TaskKind kind, String title, String message, int total, TaskWork work) {
            this.kind = kind;
            this.title = title;
            this.message = message;
            this.total = total;
            this.work = work;
        }
    }
}
