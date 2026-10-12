package com.example.aiquizlite;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/** Every redirect is explicitly validated; JVM/Android default certificate verification stays enabled. */
final class UpdateTransport {
    interface Factory { HttpURLConnection open(String url) throws IOException; }
    private UpdateTransport() { }

    static HttpURLConnection open(String source, String address, AtomicBoolean cancelled) throws IOException {
        return open(source, address, cancelled, url -> (HttpURLConnection) new URL(url).openConnection());
    }

    static HttpURLConnection open(String source, String address, AtomicBoolean cancelled, Factory factory)
            throws IOException {
        String current = address;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (cancelled != null && cancelled.get()) { throw new IOException("更新已取消"); }
            UpdateSourceRules.validateRequest(source, current);
            HttpURLConnection connection = factory.open(current);
            try {
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setUseCaches(false);
                connection.setRequestProperty("Cache-Control", "no-cache");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                if (status == 200) { return connection; }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 5) { throw new IOException("更新重定向异常"); }
                    current = new URL(new URL(current), location).toExternalForm();
                } else { throw new IOException("更新服务器返回状态码 " + status); }
            } catch (IOException | RuntimeException error) {
                connection.disconnect();
                throw error;
            }
            connection.disconnect();
        }
        throw new IOException("更新重定向次数过多");
    }
}
