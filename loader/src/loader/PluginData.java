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

public class PluginData {
    private static final String PLUGIN_API_URL = "https://api.mindustry-tool.com";

    private final String id;
    private final String name;
    private final String owner;
    private final String repo;
    private final String tag;

    public PluginData(String id, String name, String owner, String repo, String tag) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.repo = repo;
        this.tag = tag;
    }

    public String getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public String getOwner() {
        return this.owner;
    }

    public String getRepo() {
        return this.repo;
    }

    public String getTag() {
        return this.tag;
    }

    public PluginVersion getPluginVersion() throws Exception {
        int timeout = 5000;
        CompletableFuture<PluginVersion> result = new CompletableFuture<>();

        Http.get(URI.create(PLUGIN_API_URL + "/api/v4/plugins/version?owner=" + this.owner + "&repo=" + this.repo
                + "&tag=" + this.tag).toString())
                .error(error -> {
                    result.completeExceptionally(error);
                    Log.err(error);
                })
                .timeout(timeout)
                .submit(res -> {
                    String version = res.getResultAsString();
                    PluginVersion pluginVersion = PluginLoader.objectMapper.readValue(version, PluginVersion.class);

                    result.complete(pluginVersion);
                });

        return result.get(timeout, TimeUnit.MILLISECONDS);
    }

    public byte[] download() {
        try {
            URL url = URI.create(PLUGIN_API_URL + "/api/v4/plugins/download?owner=" + this.owner + "&repo=" + this.repo
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
