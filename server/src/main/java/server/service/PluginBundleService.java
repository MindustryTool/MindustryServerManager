package server.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class PluginBundleService {

    public static final String BUNDLE_JAR_PATH = "/app/plugin.jar";
    public static final String BUNDLE_HASH_PATH = "/app/plugin.sha256";

    private final byte[] jarBytes;
    private final String sha256;

    public PluginBundleService(byte[] jarBytes, String sha256) {
        this.jarBytes = Objects.requireNonNull(jarBytes, "jarBytes");
        this.sha256 = Objects.requireNonNull(sha256, "sha256").trim();
        if (this.jarBytes.length == 0) {
            throw new IllegalArgumentException("Bundled plugin.jar is empty");
        }
        if (this.sha256.isEmpty()) {
            throw new IllegalArgumentException("Bundled plugin hash is blank");
        }
    }

    public static PluginBundleService loadFromImage() {
        return loadFromPaths(Path.of(BUNDLE_JAR_PATH), Path.of(BUNDLE_HASH_PATH));
    }

    static PluginBundleService loadFromPaths(Path jarPath, Path hashPath) {
        try {
            byte[] bytes = Files.readAllBytes(jarPath);
            String hash = Files.readString(hashPath, StandardCharsets.UTF_8).trim();
            return new PluginBundleService(bytes, hash);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Missing controller plugin bundle: expected " + jarPath + " and " + hashPath, e);
        }
    }

    public String getPluginVersion() {
        return sha256;
    }

    public byte[] downloadPlugin() {
        return jarBytes;
    }

    public byte[] jarBytes() {
        return jarBytes;
    }

    public String sha256() {
        return sha256;
    }
}
