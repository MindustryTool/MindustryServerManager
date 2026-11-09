package plugin.core;

import arc.files.Fi;
import com.fasterxml.jackson.databind.JsonNode;
import mindustry.Vars;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.annotations.Configuration;
import plugin.utils.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigManagerTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;

    @BeforeEach
    void setUp() {
        Vars.dataDirectory = new Fi(tempDir.toFile());
        FileWatcherManager dummyWatcher = new FileWatcherManager() {
            @Override
            public void watch(Path target, java.util.function.Consumer<Path> callback, long debounceMs, java.nio.file.WatchEvent.Kind<?>... events) {
                // No-op for unit tests
            }
        };
        configManager = new ConfigManager(dummyWatcher);
    }

    @Configuration(value = "sample.json", version = 1)
    static class SampleConfigV1 {
        public String title = "initial";
        public int amount = 42;
    }

    @Configuration(value = "sample.json", version = 2)
    static class SampleConfigV2 {
        public String title = "updated";
        public int amount = 99;
    }

    @Test
    void testCreatesFreshConfigFileWithVersionAndPrettyPrinting() throws IOException {
        SampleConfigV1 instance = new SampleConfigV1();
        Configuration config = SampleConfigV1.class.getAnnotation(Configuration.class);

        configManager.process(config, instance);

        Path filePath = tempDir.resolve("sample.json");
        assertTrue(Files.exists(filePath));

        String content = Files.readString(filePath);
        assertTrue(content.contains("\n"), "Content should be pretty printed with newlines");

        JsonNode root = JsonUtils.getObjectMapper().readTree(content);
        assertEquals(1, root.path("version").asInt());
        assertEquals("initial", root.path("title").asText());
        assertEquals(42, root.path("amount").asInt());
    }

    @Test
    void testOverwritesOutdatedConfigFileWithCodeDefaults() throws IOException {
        Path filePath = tempDir.resolve("sample.json");
        String oldJson = "{\n  \"version\": 1,\n  \"title\": \"customOld\",\n  \"amount\": 1\n}";
        Files.writeString(filePath, oldJson);

        SampleConfigV2 instance = new SampleConfigV2();
        Configuration config = SampleConfigV2.class.getAnnotation(Configuration.class);

        configManager.process(config, instance);

        String newContent = Files.readString(filePath);
        JsonNode root = JsonUtils.getObjectMapper().readTree(newContent);

        assertEquals(2, root.path("version").asInt());
        assertEquals("updated", root.path("title").asText());
        assertEquals(99, root.path("amount").asInt());
        assertEquals("updated", instance.title);
        assertEquals(99, instance.amount);
    }

    @Test
    void testOverwritesUnversionedConfigFileWithCodeDefaults() throws IOException {
        Path filePath = tempDir.resolve("sample.json");
        String legacyJson = "{\n  \"title\": \"legacyCustom\",\n  \"amount\": 500\n}";
        Files.writeString(filePath, legacyJson);

        SampleConfigV1 instance = new SampleConfigV1();
        Configuration config = SampleConfigV1.class.getAnnotation(Configuration.class);

        configManager.process(config, instance);

        String newContent = Files.readString(filePath);
        JsonNode root = JsonUtils.getObjectMapper().readTree(newContent);

        assertEquals(1, root.path("version").asInt());
        assertEquals("initial", root.path("title").asText());
        assertEquals(42, root.path("amount").asInt());
        assertEquals("initial", instance.title);
        assertEquals(42, instance.amount);
    }

    @Test
    void testLoadsMatchingVersionWithoutOverwriting() throws IOException {
        Path filePath = tempDir.resolve("sample.json");
        String existingJson = "{\n  \"version\": 2,\n  \"title\": \"serverCustomized\",\n  \"amount\": 777\n}";
        Files.writeString(filePath, existingJson);

        SampleConfigV2 instance = new SampleConfigV2();
        Configuration config = SampleConfigV2.class.getAnnotation(Configuration.class);

        configManager.process(config, instance);

        assertEquals("serverCustomized", instance.title);
        assertEquals(777, instance.amount);

        String fileContent = Files.readString(filePath);
        assertEquals(existingJson, fileContent);
    }

    @Test
    void testLoadsNewerVersionWithWarningAndPreservesFile() throws IOException {
        Path filePath = tempDir.resolve("sample.json");
        String newerJson = "{\n  \"version\": 5,\n  \"title\": \"fromFuture\",\n  \"amount\": 1234\n}";
        Files.writeString(filePath, newerJson);

        SampleConfigV2 instance = new SampleConfigV2();
        Configuration config = SampleConfigV2.class.getAnnotation(Configuration.class);

        configManager.process(config, instance);

        assertEquals("fromFuture", instance.title);
        assertEquals(1234, instance.amount);

        String fileContent = Files.readString(filePath);
        assertEquals(newerJson, fileContent);
    }
}
