package plugin.chat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class TranslationServiceTest {

    @Test
    public void testProviderOrderingAndFallback() {
        TranslationService service = new TranslationService();

        AtomicInteger primaryCalls = new AtomicInteger(0);
        AtomicInteger backupCalls = new AtomicInteger(0);

        TranslationProvider failingPrimary = new TranslationProvider() {
            @Override
            public String name() {
                return "failing-primary";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public TranslationResult translate(String text, String targetLang) throws Exception {
                primaryCalls.incrementAndGet();
                throw new RuntimeException("Primary provider simulated network failure");
            }
        };

        TranslationProvider workingBackup = new TranslationProvider() {
            @Override
            public String name() {
                return "working-backup";
            }

            @Override
            public int getOrder() {
                return 20;
            }

            @Override
            public TranslationResult translate(String text, String targetLang) {
                backupCalls.incrementAndGet();
                return new TranslationResult("Backup: " + text, "vi");
            }
        };

        service.registerProvider(workingBackup);
        service.registerProvider(failingPrimary);

        TranslationResult result = service.translate("Xin chào", "en");

        assertNotNull(result);
        assertEquals("Backup: Xin chào", result.translatedText());
        assertEquals(1, primaryCalls.get(), "Primary should have been tried first");
        assertEquals(1, backupCalls.get(), "Backup should have been invoked after primary failure");
    }

    @Test
    public void testCaffeineCacheHits() {
        TranslationService service = new TranslationService();

        AtomicInteger callCount = new AtomicInteger(0);

        TranslationProvider provider = new TranslationProvider() {
            @Override
            public String name() {
                return "counting-provider";
            }

            @Override
            public TranslationResult translate(String text, String targetLang) {
                callCount.incrementAndGet();
                return new TranslationResult("Translated: " + text, "auto");
            }
        };

        service.registerProvider(provider);

        // First call: cache miss -> provider called
        TranslationResult first = service.translate("Hello", "vi");
        assertNotNull(first);
        assertEquals(1, callCount.get());

        // Second call: cache hit -> provider NOT called
        TranslationResult second = service.translate("Hello", "vi");
        assertNotNull(second);
        assertEquals("Translated: Hello", second.translatedText());
        assertEquals(1, callCount.get(), "Cache should prevent second invocation of provider");

        // Different language -> cache miss -> provider called
        TranslationResult third = service.translate("Hello", "es");
        assertNotNull(third);
        assertEquals(2, callCount.get());
    }

    @Test
    public void testAllProvidersFailGracefully() {
        TranslationService service = new TranslationService();

        service.registerProvider(new TranslationProvider() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public TranslationResult translate(String text, String targetLang) throws Exception {
                throw new RuntimeException("Fatal error");
            }
        });

        TranslationResult result = service.translate("hello", "en");
        assertNull(result, "Should return null gracefully when all providers fail");
    }
}
