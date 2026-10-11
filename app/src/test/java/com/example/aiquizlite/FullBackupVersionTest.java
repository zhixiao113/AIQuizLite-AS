package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.*;

public class FullBackupVersionTest {
    @Test public void newBackupsUseVersionTwoAndOldVersionRemainsReadable() {
        assertEquals(2, FullBackupRules.FORMAT_VERSION);
        assertTrue(FullBackupRules.supportsVersion(1));
        assertTrue(FullBackupRules.supportsVersion(2));
        assertFalse(FullBackupRules.supportsVersion(3));
    }
}
