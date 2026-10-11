package server.service.translation;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import common.translation.TranslationResponse;

import static org.junit.jupiter.api.Assertions.*;

public class SqliteTranslationCacheTest {

    @TempDir
    Path tempDir;

    private String dbPath(String name) {
        return tempDir.resolve(name).toString();
    }

    @Test
    public void testPersistenceAcrossRestart() {
        String path = dbPath("translations.db");
        try (SqliteTranslationCache cache = new SqliteTranslationCache(path)) {
            cache.put("vi:hello", new TranslationResponse("xin chao", "en"));
        }
        assertTrue(Files.exists(Path.of(path)));
        try (SqliteTranslationCache reopened = new SqliteTranslationCache(path)) {
            var result = reopened.get("vi:hello");
            assertTrue(result.isPresent());
            assertEquals("xin chao", result.get().getTranslatedText());
            assertEquals("en", result.get().getSourceLanguage());
        }
    }

    @Test
    public void testTtlPurgeDeletesStaleRows() {
        String path = dbPath("ttl.db");
        try (SqliteTranslationCache cache = new SqliteTranslationCache(path)) {
            cache.put("vi:hello", new TranslationResponse("xin chao", "en"));
            assertEquals(1, cache.size());
            int deleted = cache.purgeExpired(System.currentTimeMillis() + 1000);
            assertEquals(1, deleted);
            assertEquals(0, cache.size());
        }
    }

    @Test
    public void testCapacityCapDeletesLruRows() {
        String path = dbPath("cap.db");
        try (SqliteTranslationCache cache = new SqliteTranslationCache(path)) {
            for (int i = 0; i < 5; i++) {
                cache.put("vi:k" + i, new TranslationResponse("v" + i, "en"));
            }
            assertEquals(5, cache.size());
            int deleted = cache.enforceCapacity(3);
            assertEquals(2, deleted);
            assertEquals(3, cache.size());
        }
    }
}
