package com.example.aiquizlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class QuestionImageStore {
    static final String LOCAL_PREFIX = "local:";
    private static final String ROOT_DIR = "question_bank_images";
    private static final int MAX_LONG_EDGE = 1600;
    private static final int MAX_SOURCE_BYTES = 25 * 1024 * 1024;

    private QuestionImageStore() {
    }

    public static String importFromUri(Context context, String questionBankId, Uri uri) throws Exception {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("无法读取所选图片");
            }
            return importFromStream(context, questionBankId, inputStream);
        }
    }

    public static String importFromStream(
            Context context,
            String questionBankId,
            InputStream inputStream
    ) throws Exception {
        byte[] source = readLimited(inputStream, MAX_SOURCE_BYTES);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("文件不是可识别的图片");
        }
        if (bounds.outWidth > 32000 || bounds.outHeight > 32000) {
            throw new IllegalArgumentException("图片尺寸过大");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_LONG_EDGE);
        Bitmap decoded = BitmapFactory.decodeByteArray(source, 0, source.length, options);
        if (decoded == null) {
            throw new IllegalArgumentException("图片解码失败");
        }

        Bitmap oriented = applyExifOrientation(decoded, source);
        Bitmap scaled = scaleDown(oriented, MAX_LONG_EDGE);
        boolean png = scaled.hasAlpha();
        String fileName = "img_" + UUID.randomUUID().toString().replace("-", "")
                + (png ? ".png" : ".jpg");
        File target = localFile(context, questionBankId, fileName);
        boolean saved = false;
        try (FileOutputStream outputStream = new FileOutputStream(target)) {
            boolean compressed = scaled.compress(
                    png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                    png ? 100 : 88,
                    outputStream
            );
            if (!compressed) {
                throw new IllegalStateException("图片压缩失败");
            }
            outputStream.getFD().sync();
            saved = true;
        } finally {
            if (!saved) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            if (scaled != oriented) {
                scaled.recycle();
            }
            if (oriented != decoded) {
                oriented.recycle();
            }
            decoded.recycle();
        }
        return LOCAL_PREFIX + fileName;
    }

    public static InputStream open(
            Context context,
            String questionBankId,
            String imageReference
    ) throws Exception {
        if (isLocal(imageReference)) {
            return new FileInputStream(localFile(
                    context,
                    questionBankId,
                    imageReference.substring(LOCAL_PREFIX.length())
            ));
        }
        return context.getAssets().open("question_images/" + safeAssetName(imageReference));
    }

    public static String copyToBank(
            Context context,
            String sourceBankId,
            String imageReference,
            String targetBankId
    ) throws Exception {
        try (InputStream inputStream = open(context, sourceBankId, imageReference)) {
            return importFromStream(context, targetBankId, inputStream);
        }
    }

    public static void cleanupOrphans(
            Context context,
            String questionBankId,
            List<Question> questions
    ) {
        Set<String> referencedNames = new HashSet<>();
        for (Question question : questions) {
            for (String image : references(question)) {
                if (isLocal(image)) {
                    referencedNames.add(image.substring(LOCAL_PREFIX.length()));
                }
            }
        }
        File directory = bankDirectory(context, questionBankId, false);
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && !referencedNames.contains(file.getName())) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    public static void deleteBankImages(Context context, String questionBankId) {
        File directory = bankDirectory(context, questionBankId, false);
        if (directory == null || !directory.exists()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        directory.delete();
    }

    public static int countReferencedImages(List<Question> questions) {
        Set<String> references = new HashSet<>();
        for (Question question : questions) {
            for (String image : references(question)) {
                if (image != null && !image.trim().isEmpty()) references.add(image);
            }
        }
        return references.size();
    }

    static List<String> references(Question question) {
        List<String> result = new ArrayList<>();
        if (question.getImage() != null) result.add(question.getImage());
        result.addAll(question.getExplanationImages());
        return result;
    }

    public static String displayName(String imageReference) {
        if (imageReference == null || imageReference.trim().isEmpty()) {
            return "尚未添加图片";
        }
        return isLocal(imageReference)
                ? imageReference.substring(LOCAL_PREFIX.length())
                : imageReference;
    }

    static boolean isLocal(String imageReference) {
        return imageReference != null && imageReference.startsWith(LOCAL_PREFIX);
    }

    static int calculateInSampleSize(int width, int height, int maxLongEdge) {
        int sample = 1;
        while (Math.max(width / sample, height / sample) > maxLongEdge * 2) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap applyExifOrientation(Bitmap bitmap, byte[] source) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(source));
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
            );
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.postRotate(270);
                    break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    matrix.preScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    matrix.preScale(1, -1);
                    break;
                default:
                    return bitmap;
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (Exception ignored) {
            return bitmap;
        }
    }

    private static Bitmap scaleDown(Bitmap bitmap, int maxLongEdge) {
        int longEdge = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longEdge <= maxLongEdge) {
            return bitmap;
        }
        float ratio = maxLongEdge / (float) longEdge;
        int width = Math.max(1, Math.round(bitmap.getWidth() * ratio));
        int height = Math.max(1, Math.round(bitmap.getHeight() * ratio));
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    private static byte[] readLimited(InputStream inputStream, int limit) throws Exception {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                total += count;
                if (total > limit) {
                    throw new IllegalArgumentException("图片文件不能超过25MB");
                }
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toByteArray();
        }
    }

    private static File localFile(Context context, String questionBankId, String fileName) throws Exception {
        if (fileName == null || fileName.isEmpty()
                || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("图片引用不安全");
        }
        File directory = bankDirectory(context, questionBankId, true);
        File file = new File(directory, fileName);
        String directoryPath = directory.getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(directoryPath)) {
            throw new IllegalArgumentException("图片路径不安全");
        }
        return file;
    }

    private static File bankDirectory(Context context, String questionBankId, boolean create) {
        String safeBankId = questionBankId == null
                ? "unknown"
                : questionBankId.replaceAll("[^A-Za-z0-9._-]", "_");
        File root = new File(context.getFilesDir(), ROOT_DIR);
        File directory = new File(root, safeBankId);
        if (create && !directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建题库图片目录");
        }
        return directory;
    }

    private static String safeAssetName(String imageReference) {
        if (imageReference == null || imageReference.isEmpty()
                || imageReference.contains("/") || imageReference.contains("\\")
                || imageReference.contains("..")) {
            throw new IllegalArgumentException("题库图片名称不安全");
        }
        return imageReference;
    }
}
