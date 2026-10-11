package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class QuestionBankPackageManagerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void zipRejectsDuplicateQuestionIdsJustLikePlainJson() throws Exception {
        JSONArray items = questions(null);
        items.put(new JSONObject(items.getJSONObject(0).toString()));
        assertRejected("ID", "题库.json", items.toString());
    }

    @Test public void zipMetadataSurvivesModelBasedExport() throws Exception {
        JSONArray items = questions(null);
        items.getJSONObject(0).put("sourceChapter", "BGP").put("sourceName", "03卷.docx");
        QuestionBankPackageManager.PackagedBank bank = read(temp.newFolder(), "题库.json", items.toString());
        JSONObject exported = QuestionRepository.questionsToJson(
                QuestionRepository.parsePackagedQuestions(bank.questions, bank.displayName)).getJSONObject(0);
        assertEquals("BGP", exported.getString("sourceChapter"));
        assertEquals("03卷.docx", exported.getString("sourceName"));
        assertTrue(exported.isNull("image"));
    }

    @Test
    public void importsPlainArrayWithImageFolderAndSharedPicture() throws Exception {
        JSONArray questions = questions("image/示意图.png");
        questions.put(new JSONObject(questions.getJSONObject(0).toString()).put("id", "q2"));
        File directory = temp.newFolder();
        QuestionBankPackageManager.PackagedBank bank = read(directory,
                "题库.JSON", questions.toString(), "image/示意图.png", "image bytes");
        assertEquals("题库", bank.displayName);
        assertEquals(2, bank.questions.length());
        assertEquals("image/示意图.png", bank.questions.getJSONObject(1).getString("image"));
        assertEquals("image bytes", new String(Files.readAllBytes(
                new File(directory, "image/示意图.png").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void importsNamedObjectWithoutFormatVersion() throws Exception {
        JSONObject json = new JSONObject().put("name", "图形题库").put("questions", questions("image/a.png"));
        assertEquals("图形题库", read(temp.newFolder(),
                "试题.json", json.toString(), "image/a.png", "picture").displayName);
    }

    @Test
    public void acceptsLegacyExportWithImagesAndVersion() throws Exception {
        JSONObject json = new JSONObject().put("formatVersion", 1)
                .put("name", "旧题库").put("questions", questions("images/a.png"));
        QuestionBankPackageManager.PackagedBank bank = read(temp.newFolder(),
                "question-bank.json", json.toString(), "images/a.png", "picture");
        assertEquals("旧题库", bank.displayName);
        assertEquals("images/a.png", bank.questions.getJSONObject(0).getString("image"));
    }

    @Test
    public void acceptsVersionTwoMatchingWithPostSubmitExplanationImage() throws Exception {
        JSONObject matching = new JSONObject()
                .put("mode", "left_reusable")
                .put("leftItems", new JSONArray("[{\"id\":\"L1\",\"text\":\"属性 A\"}]"))
                .put("rightItems", new JSONArray("[{\"id\":\"R1\",\"text\":\"描述一\"},"
                        + "{\"id\":\"R2\",\"text\":\"描述二\"}]"))
                .put("correctPairs", new JSONArray("[{\"leftId\":\"L1\",\"rightId\":\"R1\"},"
                        + "{\"leftId\":\"L1\",\"rightId\":\"R2\"}]"));
        JSONObject question = new JSONObject().put("id", "q89").put("type", "matching")
                .put("stem", "连线题").put("matching", matching)
                .put("explanationImages", new JSONArray().put("image/answer.png"));
        JSONObject manifest = new JSONObject().put("formatVersion", 2)
                .put("name", "新版题库").put("questions", new JSONArray().put(question));
        QuestionBankPackageManager.PackagedBank bank = read(temp.newFolder(),
                "question-bank.json", manifest.toString(), "image/answer.png", "picture");
        assertEquals("新版题库", bank.displayName);
        assertEquals("image/answer.png", bank.questions.getJSONObject(0)
                .getJSONArray("explanationImages").getString(0));
    }

    @Test
    public void wrapperFolderBomAndRelativeWindowsImagePathsAreSupported() throws Exception {
        QuestionBankPackageManager.PackagedBank bank = read(temp.newFolder(),
                "试卷/题库.json", "\uFEFF" + questions(".\\image\\章节\\a.png"),
                "试卷/image/章节/a.png", "picture");
        assertEquals("试卷/image/章节/a.png", bank.questions.getJSONObject(0).getString("image"));
    }

    @Test
    public void allowsImageLessQuestionsAndEmptyDirectoryEntries() throws Exception {
        QuestionBankPackageManager.PackagedBank bank = read(temp.newFolder(),
                "image/", "", "题库.json", questions(null).toString());
        assertTrue(bank.questions.getJSONObject(0).isNull("image"));
    }

    @Test
    public void nullOrMissingImageNeverBecomesLiteralNullPath() throws Exception {
        JSONObject noImage = new JSONObject().put("image", JSONObject.NULL);
        assertEquals("", QuestionBankPackageManager.optionalImageReference(noImage));
        assertEquals("", QuestionBankPackageManager.optionalImageReference(new JSONObject()));
        assertEquals("image/q5.png", QuestionBankPackageManager.optionalImageReference(
                new JSONObject().put("image", " image/q5.png ")));
    }

    @Test
    public void resolvesBareImageNamesButRejectsAmbiguousMatches() throws Exception {
        QuestionBankPackageManager.PackagedBank bank = read(temp.newFolder(),
                "题库.json", questions("a.png").toString(), "image/a.png", "picture");
        assertEquals("image/a.png", bank.questions.getJSONObject(0).getString("image"));
        bank = read(temp.newFolder(), "题库.json", questions("a.png").toString(),
                "images/a.png", "picture");
        assertEquals("images/a.png", bank.questions.getJSONObject(0).getString("image"));
        assertRejected("图片名不唯一", "题库.json", questions("a.png").toString(),
                "image/a.png", "one", "images/a.png", "two");
    }

    @Test
    public void rejectsPayloadHiddenInDirectoryEntry() throws Exception {
        assertRejected("目录条目不能包含文件内容", "image/", "hidden payload");
    }

    @Test
    public void rejectsMissingPictureInsteadOfSilentlyDroppingIt() throws Exception {
        assertRejected("图片不存在", "题库.json", questions("image/missing.png").toString());
    }

    @Test
    public void rejectsAmbiguousJsonFiles() throws Exception {
        assertRejected("多个题库 JSON", "a.json", questions(null).toString(),
                "b.json", questions(null).toString());
    }

    @Test
    public void rejectsMissingJsonAndEmptyQuestionArray() throws Exception {
        assertRejected("缺少题库 JSON", "image/a.png", "picture");
        assertRejected("没有题目", "题库.json", "[]");
    }

    @Test
    public void rejectsUnsupportedVersionAndMalformedQuestions() throws Exception {
        assertRejected("不支持的题库包版本", "question-bank.json",
                new JSONObject().put("formatVersion", 3).put("questions", questions(null)).toString());
        JSONArray noAnswer = questions(null);
        noAnswer.getJSONObject(0).put("answers", new JSONArray());
        assertRejected("缺少有效答案", "题库.json", noAnswer.toString());
        assertRejected("图片引用必须是相对路径", "题库.json", questions(null)
                .put(0, new JSONObject(questions(null).getJSONObject(0).toString()).put("image", 123)).toString());
        JSONArray unknownType = questions(null);
        unknownType.getJSONObject(0).put("type", "future_unhandled_type");
        assertRejected("题型不受支持", "题库.json", unknownType.toString());
    }

    @Test
    public void rejectsTraversalAndAbsolutePathsBeforeWritingOutsideDestination() throws Exception {
        File destination = temp.newFolder();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> read(destination,
                "../escape.json", questions(null).toString()));
        assertTrue(error.getMessage().contains("不安全路径"));
        assertFalse(new File(destination.getParentFile(), "escape.json").exists());
        assertRejected("绝对路径", "/tmp/题库.json", questions(null).toString());
        assertRejected("绝对路径", "C:/题库.json", questions(null).toString());
        assertRejected("不安全路径", "image/../../escape.png", "picture");
        assertRejected("不安全路径", "image/a.png:stream", "picture");
    }

    @Test
    public void rejectsTraversalInQuestionImageReferences() throws Exception {
        assertRejected("不安全路径", "题库.json", questions("image/../outside.png").toString());
        assertRejected("不安全路径", "wrapper/题库.json", questions("../image/a.png").toString(),
                "image/a.png", "picture");
    }

    @Test
    public void rejectsDuplicateNormalizedArchivePaths() throws Exception {
        assertRejected("重复文件", "image/a.png", "one", "image\\a.png", "two");
        assertRejected("重复文件", "image/a.png", "one", "image/A.PNG", "two");
    }

    @Test
    public void rejectsTooManyEntriesIncludingDirectories() throws Exception {
        String[] entries = new String[1002];
        for (int i = 0; i < 501; i++) {
            entries[i * 2] = "image/d" + i + "/";
            entries[i * 2 + 1] = "";
        }
        assertRejected("文件数量过多", entries);
    }

    @Test
    public void rejectsOversizedUncompressedImage() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("image/large.png"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 26; i++) {
                zip.write(block);
            }
            zip.closeEntry();
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> QuestionBankPackageManager.readPackage(
                        new ByteArrayInputStream(bytes.toByteArray()), temp.newFolder()));
        assertTrue(error.getMessage().contains("内容过大"));
    }

    @Test
    public void skipsSystemJunkEntriesBeforeImporting() throws Exception {
        QuestionBankPackageManager.PackagedBank bank = read(temp.newFolder(),
                "question-bank.json", questions(null).toString(),
                ".DS_Store", "junk",
                "__MACOSX/._question-bank.json", "junk",
                "Thumbs.db", "junk",
                "image/a.png", "picture");
        assertEquals(1, bank.questions.length());
    }

    private void assertRejected(String message, String... entries) throws Exception {
        File directory = temp.newFolder();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> read(directory, entries));
        assertTrue(error.getMessage(), error.getMessage().contains(message));
    }

    private QuestionBankPackageManager.PackagedBank read(File directory, String... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return QuestionBankPackageManager.readPackage(new ByteArrayInputStream(bytes.toByteArray()), directory);
    }

    private JSONArray questions(String image) throws Exception {
        return new JSONArray().put(new JSONObject()
                .put("id", "q1").put("type", "single").put("stem", "图中是什么？")
                .put("options", new JSONArray("[{\"key\":\"A\",\"text\":\"三角形\"},{\"key\":\"B\",\"text\":\"圆形\"}]"))
                .put("answers", new JSONArray().put("A"))
                .put("image", image == null ? JSONObject.NULL : image));
    }
}
