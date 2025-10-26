package loader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import arc.util.Http;
import arc.util.Log;
import lombok.Data;

@Data
public class PluginData {
    private static final String PLUGIN_API_URL = "https://api.mindustry-tool.com";

    private final String id;
    private final String name;
    private final String url;

    public PLuginVersion getPluginVersion() throws Exception {
        int timeout = 5000;
        CompletableFuture<PLuginVersion> result = new CompletableFuture<>();

        Http.get(URI.create(PLUGIN_API_URL + "/api/v4/plugins/version?path=" + this.url).toString())
                .error(error -> {
                    result.completeExceptionally(error);
                    Log.err(error);
                })
                .timeout(timeout)
                .submit(res -> {
                    String version = res.getResultAsString();
                    PLuginVersion pluginVersion = PluginLoader.objectMapper.readValue(version, PLuginVersion.class);

                    result.complete(pluginVersion);
                });

        return result.get(timeout, TimeUnit.MILLISECONDS);
    }

    public byte[] download() {
        try {
            URL url = URI.create(PLUGIN_API_URL + "/api/v4/plugins/download?path=" + this.url).toURL();

            HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();

            httpConn.setReadTimeout(60_000);
            httpConn.setConnectTimeout(60_000);

            int responseCode = httpConn.getResponseCode();

            try (BufferedInputStream in = new BufferedInputStream(httpConn.getInputStream());
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                } else {
                    throw new IOException("Server returned non-OK response code: " + responseCode);
                }

                httpConn.disconnect();

                return out.toByteArray();
            }
        } catch (Exception e) {
            throw new RuntimeException("Error while downloading plugin: " + url, e);
        }
    }

    @Data
    public static class PLuginVersion {
        private String updatedAt;
    }
}
