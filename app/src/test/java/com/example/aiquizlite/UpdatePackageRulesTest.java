package com.example.aiquizlite;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;
import static org.junit.Assert.*;

public class UpdatePackageRulesTest {
    private static final String PACKAGE = "com.example.aiquizlite";
    private static final Set<String> SIGNER = Collections.singleton("release-certificate");

    private String check(long installed, String pkg, long code, String name, Set<String> signers) {
        return UpdatePackageRules.validationError(PACKAGE, installed, pkg, code, name,
                7, "1.0.6", SIGNER, signers);
    }

    @Test public void sameSignerNewVersionIsAllowed() {
        assertNull(check(6, PACKAGE, 7, "1.0.6", SIGNER));
    }

    @Test public void switchedSigningKeyIsBlockedBeforeInstaller() {
        assertTrue(check(6, PACKAGE, 7, "1.0.6", Collections.singleton("debug-certificate"))
                .contains("签名不同"));
    }

    @Test public void staleApkUnderNewManifestIsBlocked() {
        assertTrue(check(5, PACKAGE, 5, "1.0.4", SIGNER).contains("实际版本"));
    }

    @Test public void mismatchedVersionNameIsBlocked() {
        assertTrue(check(6, PACKAGE, 7, "1.0.5", SIGNER).contains("实际版本"));
    }

    @Test public void sameVersionAndDowngradeAreBlocked() {
        assertNotNull(check(7, PACKAGE, 7, "1.0.6", SIGNER));
        assertNotNull(check(8, PACKAGE, 7, "1.0.6", SIGNER));
    }

    @Test public void wrongPackageIsBlocked() {
        assertNotNull(check(6, "another.app", 7, "1.0.6", SIGNER));
    }

    @Test public void missingSignaturesAreBlocked() {
        assertNotNull(check(6, PACKAGE, 7, "1.0.6", Collections.emptySet()));
        assertNotNull(UpdatePackageRules.validationError(PACKAGE, 6, PACKAGE, 7, "1.0.6",
                7, "1.0.6", Collections.emptySet(), Collections.emptySet()));
    }

    @Test public void multipleSignersRequireExactSet() {
        assertNotNull(check(6, PACKAGE, 7, "1.0.6",
                new HashSet<>(Arrays.asList("release-certificate", "other-certificate"))));
    }
}
