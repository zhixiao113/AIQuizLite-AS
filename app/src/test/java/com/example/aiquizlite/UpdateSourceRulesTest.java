package com.example.aiquizlite;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateSourceRulesTest {
    @Test public void githubManifestIsNotDirectory() {
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST, UpdateSourceRules.manifestUrl(UpdateSourceRules.GITHUB_MANIFEST));
    }
    @Test public void localRootKeepsVersionJsonProtocol() {
        assertEquals("https://example.org/update/version.json", UpdateSourceRules.manifestUrl(" https://example.org/update/ "));
    }
    @Test public void onlyExplicitPrivateHttpAllowed() {
        for (String host : new String[]{"10.42.78.226", "172.16.1.2", "172.31.1.2", "127.0.0.1", "192.168.1.2"}) {
            assertTrue(UpdateSourceRules.privateIpv4(host));
            UpdateSourceRules.manifestUrl("http://" + host + ":4387");
        }
        for (String host : new String[]{"8.8.8.8", "172.32.1.2", "10.0.0.256", "127.1", "localhost", "010.0.0.1"}) {
            assertFalse(UpdateSourceRules.privateIpv4(host));
            assertThrows(IllegalArgumentException.class, () -> UpdateSourceRules.manifestUrl("http://" + host));
        }
    }
    @Test public void credentialsFragmentsAndQueriesRejected() {
        for (String url : new String[]{"https://token@github.com", "https://example.org/#x", "https://example.org/?token=x", "file:///tmp"}) {
            assertThrows(IllegalArgumentException.class, () -> UpdateSourceRules.manifestUrl(url));
        }
    }
    @Test public void cdnAllowedButLookalikeNotAllowed() {
        UpdateSourceRules.validateRequest(UpdateSourceRules.GITHUB_MANIFEST,
                "https://release-assets.githubusercontent.com/assets/a?sig=temporary");
        assertThrows(IllegalArgumentException.class, () -> UpdateSourceRules.validateRequest(UpdateSourceRules.GITHUB_MANIFEST,
                "https://release-assets.githubusercontent.com.evil.org/a"));
    }
    @Test public void downgradeAndLocalRedirectCrossOriginFail() {
        assertThrows(IllegalArgumentException.class, () -> UpdateSourceRules.validateRequest(UpdateSourceRules.GITHUB_MANIFEST,
                "http://192.168.1.2/app.apk"));
        assertThrows(IllegalArgumentException.class, () -> UpdateSourceRules.validateRequest("https://example.org",
                "https://other.org/app.apk"));
    }
    @Test public void githubCandidatesKeepCanonicalFirstAndAddReachableFallbacks() {
        java.util.List<String> candidates = UpdateSourceRules.manifestCandidates(UpdateSourceRules.GITHUB_MANIFEST);
        assertEquals(4, candidates.size());
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST, candidates.get(0));
        assertTrue(candidates.contains("https://github.com/zhixiao113/AIQuizLite-AS/releases/latest/download/release-manifest.json"));
        assertTrue(candidates.contains("https://fastly.jsdelivr.net/gh/zhixiao113/AIQuizLite-AS@update-channel/stable.json"));
        assertTrue(candidates.contains("https://gcore.jsdelivr.net/gh/zhixiao113/AIQuizLite-AS@update-channel/stable.json"));
        for (String candidate : candidates) {
            assertTrue(candidate.startsWith("https://"));
            UpdateSourceRules.validateRequest(UpdateSourceRules.GITHUB_MANIFEST, candidate);
        }
    }
    @Test public void localSourceKeepsSingleCandidate() {
        java.util.List<String> candidates = UpdateSourceRules.manifestCandidates(" https://example.org/update/ ");
        assertEquals(java.util.Collections.singletonList("https://example.org/update/version.json"), candidates);
        assertThrows(IllegalArgumentException.class, () -> UpdateSourceRules.manifestCandidates("http://8.8.8.8"));
    }
    @Test public void mirrorHostsAllowedButLookalikesAndPlainHttpRejected() {
        UpdateSourceRules.validateRequest(UpdateSourceRules.GITHUB_MANIFEST,
                "https://fastly.jsdelivr.net/gh/zhixiao113/AIQuizLite-AS@update-channel/stable.json");
        UpdateSourceRules.validateRequest(UpdateSourceRules.GITHUB_MANIFEST,
                "https://github.com/zhixiao113/AIQuizLite-AS/releases/latest/download/release-manifest.json");
        for (String address : new String[]{
                "https://fastly.jsdelivr.net.evil.org/gh/zhixiao113/AIQuizLite-AS@update-channel/stable.json",
                "https://cdn.jsdelivr.net/gh/zhixiao113/AIQuizLite-AS@update-channel/stable.json",
                "http://fastly.jsdelivr.net/gh/zhixiao113/AIQuizLite-AS@update-channel/stable.json",
                "https://raw.githubusercontent.com:8443/zhixiao113/AIQuizLite-AS/update-channel/stable.json"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> UpdateSourceRules.validateRequest(UpdateSourceRules.GITHUB_MANIFEST, address));
        }
    }
}
