package com.example.aiquizlite;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateManifestTest {
    private JSONObject manifest() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("packageName", "com.example.aiquizlite")
                .put("versionCode", 14).put("versionName", "1.1.3")
                .put("apkUrl", "https://github.com/zhixiao113/AIQuizLite-AS/releases/download/v1.1.3/app-release.apk")
                .put("apkFile", "AIQuizLite-v14-example.apk").put("apkSize", 13658724)
                .put("signerSha256", UpdateSourceRules.SIGNER).put("apkSha256", "a".repeat(64))
                .put("notes", "更新说明");
    }
    private UpdateManifest parse(JSONObject data) throws Exception {
        return UpdateManifest.parse(UpdateSourceRules.GITHUB_MANIFEST, data.toString());
    }
    @Test public void githubUsesFixedAssetAndBoundSource() throws Exception {
        UpdateManifest result = parse(manifest());
        assertEquals(14, result.versionCode);
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST, result.source);
        assertTrue(result.apkUrl.contains("/v1.1.3/"));
        assertEquals(13658724, result.apkSize);
    }
    @Test public void legacyPublisherProtocolStillWorksWithoutSize() throws Exception {
        JSONObject data = manifest(); data.remove("apkSize"); data.remove("apkUrl"); data.remove("schemaVersion");
        UpdateManifest result = UpdateManifest.parse("http://192.168.1.2:4387", data.toString());
        assertEquals("http://192.168.1.2:4387/AIQuizLite-v14-example.apk", result.apkUrl);
        assertEquals(0, result.apkSize);
    }
    @Test public void missingHashIsNotOptional() throws Exception {
        JSONObject data = manifest(); data.remove("apkSha256");
        assertThrows(IllegalArgumentException.class, () -> parse(data));
    }
    @Test public void wrongSignerRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse(manifest().put("signerSha256", "b".repeat(64))));
    }
    @Test public void wrongPackageRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse(manifest().put("packageName", "other")));
    }
    @Test public void invalidSizeRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse(manifest().put("apkSize", 0)));
        assertThrows(IllegalArgumentException.class, () -> parse(manifest().put("apkSize", UpdateSourceRules.MAX_APK_BYTES + 1)));
    }
    @Test public void versionTypesAndOverflowRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse(manifest().put("versionCode", "14")));
        assertThrows(IllegalArgumentException.class, () -> parse(manifest().put("versionCode", 14.2)));
        assertThrows(IllegalArgumentException.class, () -> parse(manifest().put("versionCode", 0)));
        assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(UpdateSourceRules.GITHUB_MANIFEST,
                manifest().toString().replace("\"versionCode\":14", "\"versionCode\":18446744073709551630")));
    }
    @Test public void mutableLatestUrlRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse(manifest().put("apkUrl",
                "https://github.com/zhixiao113/AIQuizLite-AS/releases/latest/download/app-release.apk")));
    }
    @Test public void anotherRepositoryRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse(manifest().put("apkUrl",
                "https://github.com/other/repo/releases/download/v1.1.3/app.apk")));
    }
    @Test public void invalidJsonAndOversizedMetadataFail() throws Exception {
        assertThrows(Exception.class, () -> UpdateManifest.parse(UpdateSourceRules.GITHUB_MANIFEST, "<html>login</html>"));
        assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(UpdateSourceRules.GITHUB_MANIFEST,
                "x".repeat(UpdateSourceRules.MAX_MANIFEST_BYTES + 1)));
    }
    @Test public void invalidLegacyPathRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse("https://example.org",
                manifest().put("apkFile", "../other.apk").toString()));
    }
}
