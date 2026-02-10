package plugin;

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
import plugin.utils.JsonUtils;

@Data
public class PluginData {
    private static final String PLUGIN_API_URL = "https://api.mindustry-tool.com";

    private final String id;
    private final String path;
    private final String owner;
    private final String repo;
    private final String tag;

    public PluginData(String id, String path, String owner, String repo, String tag) {
        this.id = id;
        this.path = path;
        this.owner = owner;
        this.repo = repo;
        this.tag = tag;
    }

    public PluginVersion getPluginVersion() {
        try {
            CompletableFuture<PluginVersion> result = new CompletableFuture<>();

            Http.get(URI.create(PLUGIN_API_URL + "/api/v4/plugins/version?repo=" + this.repo + "&owner=" + this.owner
                    + "&tag=" + this.tag).toString())
                    .error(error -> {
                        result.completeExceptionally(error);
                        Log.err(error);
                    })
                    .timeout(5000)
                    .submit(res -> {
                        String version = res.getResultAsString();
                        PluginVersion pluginVersion = JsonUtils.readJsonAsClass(version, PluginVersion.class);

                        result.complete(pluginVersion);
                    });

            var data = result.get(5000, TimeUnit.MILLISECONDS);

            return data;
        } catch (Exception e) {
            throw new RuntimeException("Error while getting plugin version " + this.id + ", " + e.getMessage());
        }
    }

    public byte[] download() {
        try {
            URL url = URI.create(PLUGIN_API_URL + "/api/v4/plugins/download?repo=" + this.repo + "&owner=" + this.owner
                    + "&tag=" + this.tag).toURL();

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
            throw new RuntimeException("Error while downloading plugin: " + this.id, e);
        }
    }

    public static class PluginVersion {
        private String updatedAt;

        public PluginVersion() {
        }

        public String getUpdatedAt() {
            return this.updatedAt;
        }
    }
}
