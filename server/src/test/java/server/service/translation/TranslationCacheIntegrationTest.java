package server.service.translation;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.benmanes.caffeine.cache.Caffeine;

import common.translation.TranslationResponse;

import static org.junit.jupiter.api.Assertions.*;

public class TranslationCacheIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    public void testL1L2FallbackAndRestartSurvival() throws Exception {
        String dbPath = tempDir.resolve("t.db").toString();
        AtomicInteger calls = new AtomicInteger(0);
        TranslationProvider provider = new TranslationProvider() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public TranslationResponse translate(String text, String targetLang) {
                calls.incrementAndGet();
                return new TranslationResponse("warm:" + text, "en");
            }
        };

        try (SqliteTranslationCache l2 = new SqliteTranslationCache(dbPath)) {
            TranslationService svc = new TranslationService(Caffeine.newBuilder().build(), l2, provider);
            TranslationResponse first = svc.translate("hello", "vi");
            assertNotNull(first);
            assertEquals(1, calls.get());
            TranslationResponse second = svc.translate("hello", "vi");
            assertEquals(1, calls.get());
            assertEquals(first.getTranslatedText(), second.getTranslatedText());
            long deadline = System.currentTimeMillis() + 5000;
            while (l2.size() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, l2.size());
        }

        try (SqliteTranslationCache l2 = new SqliteTranslationCache(dbPath)) {
            TranslationProvider failing = new TranslationProvider() {
                @Override
                public String name() {
                    return "must-not-call";
                }

                @Override
                public TranslationResponse translate(String text, String lang) {
                    throw new AssertionError("upstream should not be contacted on warm L2");
                }
            };
            TranslationService svc2 = new TranslationService(Caffeine.newBuilder().build(), l2, failing);
            TranslationResponse after = svc2.translate("hello", "vi");
            assertNotNull(after);
            assertEquals("warm:hello", after.getTranslatedText());
        }
    }
}
