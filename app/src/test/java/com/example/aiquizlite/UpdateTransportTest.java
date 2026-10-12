package com.example.aiquizlite;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateTransportTest {
    private static class Connection extends HttpURLConnection {
        int status; String location; boolean disconnected;
        Connection(int status, String location) throws Exception { super(new URL("https://github.com")); this.status=status; this.location=location; }
        public int getResponseCode() { return status; }
        public String getHeaderField(String field) { return "Location".equals(field) ? location : null; }
        public void disconnect() { disconnected=true; }
        public boolean usingProxy() { return false; }
        public void connect() { }
    }
    @Test public void redirectChecksEachHopAndClosesPriorResponse() throws Exception {
        Connection first = new Connection(302, "https://release-assets.githubusercontent.com/file");
        Connection second = new Connection(200, null);
        AtomicBoolean opened = new AtomicBoolean();
        HttpURLConnection result = UpdateTransport.open(UpdateSourceRules.GITHUB_MANIFEST,
                UpdateSourceRules.GITHUB_MANIFEST, null, url -> opened.getAndSet(true) ? second : first);
        assertSame(second, result); assertTrue(first.disconnected); assertFalse(result.getInstanceFollowRedirects());
    }
    @Test public void untrustedRedirectBlockedBeforeOpening() throws Exception {
        Connection first = new Connection(302, "https://evil.org/file");
        int[] count={0};
        assertThrows(IllegalArgumentException.class, () -> UpdateTransport.open(UpdateSourceRules.GITHUB_MANIFEST,
                UpdateSourceRules.GITHUB_MANIFEST, null, url -> { count[0]++; return first; }));
        assertEquals(1,count[0]); assertTrue(first.disconnected);
    }
    @Test public void redirectLoopBounded() throws Exception {
        Connection looping=new Connection(302,UpdateSourceRules.GITHUB_MANIFEST);
        int[] count={0};
        assertThrows(java.io.IOException.class, () -> UpdateTransport.open(UpdateSourceRules.GITHUB_MANIFEST,
                UpdateSourceRules.GITHUB_MANIFEST,null,url -> { count[0]++; return looping; }));
        assertEquals(6,count[0]); assertTrue(looping.disconnected);
    }
    @Test public void errorStatusDisconnectsAndFails() throws Exception {
        Connection missing=new Connection(404,null);
        assertThrows(java.io.IOException.class, () -> UpdateTransport.open(UpdateSourceRules.GITHUB_MANIFEST,
                UpdateSourceRules.GITHUB_MANIFEST,null,url -> missing));
        assertTrue(missing.disconnected);
    }
    @Test public void cancellationDoesNotOpenConnection() {
        assertThrows(java.io.IOException.class, () -> UpdateTransport.open(UpdateSourceRules.GITHUB_MANIFEST,
                UpdateSourceRules.GITHUB_MANIFEST,new AtomicBoolean(true),url -> { throw new AssertionError(); }));
    }
}
