package com.example.aiquizlite;

import java.net.URI;
import java.util.Locale;

/** Pure policy: no credentials, global TLS overrides or implicit source fallback. */
final class UpdateSourceRules {
    static final String REPOSITORY = "zhixiao113/AIQuizLite-AS";
    static final String GITHUB_MANIFEST = "https://raw.githubusercontent.com/" + REPOSITORY
            + "/update-channel/stable.json";
    static final String LEGACY_SERVER = "https://frp-dad.com:41717";
    static final String SIGNER = "6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8";
    static final long MAX_APK_BYTES = 128L * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 128 * 1024;

    private UpdateSourceRules() { }

    static String normalize(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) { result = result.substring(0, result.length() - 1); }
        return result;
    }

    static boolean isGithub(String source) { return GITHUB_MANIFEST.equals(normalize(source)); }

    static String manifestUrl(String source) {
        String normalized = normalize(source);
        if (isGithub(normalized)) { return GITHUB_MANIFEST; }
        URI base = uri(normalized);
        if (base.getQuery() != null) { throw invalid(); }
        validateTransport(base);
        return normalized + "/version.json";
    }

    static String localApkUrl(String source, String file) {
        if (file == null || !file.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")) { throw invalid(); }
        manifestUrl(source);
        return normalize(source) + "/" + file;
    }

    static void validateGithubApk(String value) {
        URI parsed = uri(value);
        validateTransport(parsed);
        String prefix = "/" + REPOSITORY + "/releases/download/";
        String path = parsed.getRawPath();
        if (!"github.com".equalsIgnoreCase(parsed.getHost()) || parsed.getPort() != -1
                || parsed.getQuery() != null || !path.startsWith(prefix)
                || !path.substring(prefix.length()).matches("v[0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")) {
            throw invalid();
        }
    }

    static void validateRequest(String source, String address) {
        URI target = uri(address);
        validateTransport(target);
        if (isGithub(source)) {
            String host = target.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(target.getScheme()) || target.getPort() != -1
                    || !(host.equals("raw.githubusercontent.com") || host.equals("github.com")
                    || host.equals("release-assets.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com")
                    || host.equals("objects-origin.githubusercontent.com")
                    || host.equals("github-releases.githubusercontent.com"))) { throw invalid(); }
        } else {
            URI base = uri(normalize(source));
            validateTransport(base);
            if (!base.getScheme().equals(target.getScheme())
                    || !base.getHost().equalsIgnoreCase(target.getHost())
                    || base.getPort() != target.getPort()) { throw invalid(); }
        }
    }

    private static void validateTransport(URI value) {
        if (!("https".equals(value.getScheme())
                || ("http".equals(value.getScheme()) && privateIpv4(value.getHost())))) { throw invalid(); }
    }

    static boolean privateIpv4(String host) {
        if (host == null || !host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) { return false; }
        String[] parts = host.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].length() > 3 || (parts[i].length() > 1 && parts[i].startsWith("0"))) { return false; }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) { return false; }
        }
        return octets[0] == 127 || octets[0] == 10
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31);
    }

    private static URI uri(String value) {
        try {
            URI result = new URI(value);
            if (result.getHost() == null || result.getRawUserInfo() != null || result.getFragment() != null
                    || !result.isAbsolute() || value.contains("\\")) { throw invalid(); }
            return result;
        } catch (java.net.URISyntaxException error) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("更新地址不安全或格式错误，请检查更新源设置。");
    }
}
