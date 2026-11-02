package com.example.aiquizlite;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure policy: no credentials, no TLS overrides and no user-supplied mirrors; only fixed endpoints. */
final class UpdateSourceRules {
    static final String REPOSITORY = "zhixiao113/AIQuizLite-AS";
    static final String CHANNEL_BRANCH = "update-channel";
    /** Canonical public channel; its value is also the persisted identity of the GitHub source. */
    static final String GITHUB_MANIFEST = "https://raw.githubusercontent.com/" + REPOSITORY
            + "/" + CHANNEL_BRANCH + "/stable.json";
    /**
     * Ordered, fixed manifest endpoints for the GitHub channel. Order matters:
     * <ol>
     *   <li>canonical branch file: authoritative and correct after a rollback;</li>
     *   <li>newest verified release asset: same content, served from github.com, which stays
     *       reachable on networks that reset raw.githubusercontent.com (mainland China);</li>
     *   <li>jsDelivr mirrors: last resort when all of github.com is blocked, may lag a release.</li>
     * </ol>
     * Every endpoint is HTTPS with default certificate verification, and the manifest body is
     * still bound to the original signer, package name and APK digest before anything is offered.
     */
    static final List<String> GITHUB_MANIFESTS = Collections.unmodifiableList(Arrays.asList(
            GITHUB_MANIFEST,
            "https://github.com/" + REPOSITORY + "/releases/latest/download/release-manifest.json",
            "https://fastly.jsdelivr.net/gh/" + REPOSITORY + "@" + CHANNEL_BRANCH + "/stable.json",
            "https://gcore.jsdelivr.net/gh/" + REPOSITORY + "@" + CHANNEL_BRANCH + "/stable.json"));
    private static final Set<String> GITHUB_HOSTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "raw.githubusercontent.com",
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
            "objects-origin.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "fastly.jsdelivr.net",
            "gcore.jsdelivr.net")));
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

    /** Ordered manifest endpoints to try; a local source keeps its single fixed protocol. */
    static List<String> manifestCandidates(String source) {
        String normalized = normalize(source);
        if (isGithub(normalized)) { return GITHUB_MANIFESTS; }
        return Collections.singletonList(manifestUrl(normalized));
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
                    || !GITHUB_HOSTS.contains(host)) { throw invalid(); }
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
