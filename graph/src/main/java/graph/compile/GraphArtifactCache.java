package graph.compile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class GraphArtifactCache {

    private static final String JAR_NAME = "graph.jar";
    private static final String SOURCEMAP_NAME = "sourcemap.json";

    private final Path root;

    public GraphArtifactCache(Path root) {
        this.root = root;
    }

    public static String cacheKey(String canonicalDocumentSha256, String compilerVersion,
                                  int schemaVersion, int abiVersion,
                                  String registryFingerprint) {
        return shortHash(canonicalDocumentSha256 + "|" + compilerVersion
                + "|schema=" + schemaVersion + "|abi=" + abiVersion
                + "|" + registryFingerprint);
    }

    public Optional<Map<String, byte[]>> load(String key) {
        Path jar = root.resolve(sanitizeKey(key)).resolve(JAR_NAME);
        if (!Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        try {
            Map<String, byte[]> classes = new HashMap<>();
            try (java.util.zip.ZipInputStream zip = open(jar)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }
                    classes.put(entry.getName().replace('/', '.')
                                    .replaceAll("\\.class$", ""),
                            readAll(zip));
                }
            }
            if (classes.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(classes);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<String> loadSourceMap(String key) {
        Path file = root.resolve(sanitizeKey(key)).resolve(SOURCEMAP_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void store(String key, Map<String, byte[]> classes, SourceMap sourceMap)
            throws IOException {
        Path entryDir = root.resolve(sanitizeKey(key));
        Files.createDirectories(entryDir);
        Path jar = entryDir.resolve(JAR_NAME);
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : sorted(classes)) {
                zip.putNextEntry(new java.util.zip.ZipEntry(
                        entry.getKey().replace('.', '/') + ".class"));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        Files.writeString(entryDir.resolve(SOURCEMAP_NAME),
                serialize(sourceMap), StandardCharsets.UTF_8);
    }

    public boolean remove(String key) throws IOException {
        Path entryDir = root.resolve(sanitizeKey(key));
        if (!Files.isDirectory(entryDir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(entryDir)) {
            List<Path> files = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : files) {
                Files.delete(path);
            }
        }
        return true;
    }

    public boolean contains(String key) {
        return Files.isRegularFile(root.resolve(sanitizeKey(key)).resolve(JAR_NAME));
    }

    private static String sanitizeKey(String key) {
        if (!key.matches("[a-f0-9]{8,64}")) {
            throw new IllegalArgumentException("Cache key must be lowercase hex: " + key);
        }
        return key;
    }

    private static List<Map.Entry<String, byte[]>> sorted(Map<String, byte[]> classes) {
        List<Map.Entry<String, byte[]>> list = new ArrayList<>(classes.entrySet());
        list.sort(Map.Entry.comparingByKey());
        return list;
    }

    private static java.util.zip.ZipInputStream open(Path path) throws IOException {
        return new java.util.zip.ZipInputStream(Files.newInputStream(path));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static String serialize(SourceMap map) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"graphId\":\"").append(map.graphId()).append("\",");
        sb.append("\"className\":\"").append(map.className()).append("\",");
        sb.append("\"mappings\":[");
        List<SourceMap.Mapping> mappings = map.mappings();
        for (int i = 0; i < mappings.size(); i++) {
            SourceMap.Mapping m = mappings.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"lineStart\":").append(m.lineStart())
                    .append(",\"lineEnd\":").append(m.lineEnd())
                    .append(",\"nodeId\":\"").append(m.nodeId()).append("\"");
            if (m.functionId() != null) {
                sb.append(",\"functionId\":\"").append(m.functionId()).append('"');
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
