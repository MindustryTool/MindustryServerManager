package server.service;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Caffeine;

import dto.TranslationResponseDto;

import static org.junit.jupiter.api.Assertions.*;

public class TranslationServiceTest {

    @Test
    public void testCaffeineCachePreventsDuplicateProviderCalls() {
        AtomicInteger callCount = new AtomicInteger(0);

        TranslationProvider provider = new TranslationProvider() {
            @Override
            public String name() {
                return "counting-provider";
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                callCount.incrementAndGet();
                return new TranslationResponseDto("Translated: " + text, "auto");
            }
        };

        TranslationService service = new TranslationService(Caffeine.newBuilder().build(), provider);

        // First call: cache miss -> provider called
        TranslationResponseDto first = service.translate("Hello", "vi");
        assertNotNull(first);
        assertEquals("Translated: Hello", first.getTranslatedText());
        assertEquals(1, callCount.get());

        // Second call: cache hit -> provider not called
        TranslationResponseDto second = service.translate("Hello", "vi");
        assertNotNull(second);
        assertEquals("Translated: Hello", second.getTranslatedText());
        assertEquals(1, callCount.get());
    }

    @Test
    public void testUnavailableProviderSkippedForNextAvailableProvider() {
        TranslationProvider unavailablePrimary = new TranslationProvider() {
            @Override
            public String name() {
                return "unavailable-primary";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                throw new UnsupportedOperationException("Should not be called");
            }
        };

        AtomicInteger backupCalls = new AtomicInteger(0);
        TranslationProvider availableBackup = new TranslationProvider() {
            @Override
            public String name() {
                return "available-backup";
            }

            @Override
            public int getOrder() {
                return 20;
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                backupCalls.incrementAndGet();
                return new TranslationResponseDto("Backup: " + text, "en");
            }
        };

        TranslationService service = new TranslationService(Caffeine.newBuilder().build(), unavailablePrimary, availableBackup);

        TranslationResponseDto result = service.translate("test", "vi");
        assertNotNull(result);
        assertEquals("Backup: test", result.getTranslatedText());
        assertEquals(1, backupCalls.get());
    }

    @Test
    public void testActiveProviderFailureFailsImmediatelyWithoutTryingRemainingProviders() {
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
            public TranslationResponseDto translate(String text, String targetLang) throws Exception {
                primaryCalls.incrementAndGet();
                throw new RuntimeException("Simulated API failure");
            }
        };

        TranslationProvider backup = new TranslationProvider() {
            @Override
            public String name() {
                return "backup";
            }

            @Override
            public int getOrder() {
                return 20;
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                backupCalls.incrementAndGet();
                return new TranslationResponseDto("Backup text", "en");
            }
        };

        TranslationService service = new TranslationService(Caffeine.newBuilder().build(), failingPrimary, backup);

        TranslationResponseDto result = service.translate("test", "vi");
        assertNull(result, "Should fail immediately and return null on active provider failure");
        assertEquals(1, primaryCalls.get(), "Primary should be called");
        assertEquals(0, backupCalls.get(), "Backup should NOT be called when active provider fails");
    }

    @Test
    public void testAllProvidersUnavailableReturnsNull() {
        TranslationProvider provider = new TranslationProvider() {
            @Override
            public String name() {
                return "cooling-down";
            }

            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                return null;
            }
        };

        TranslationService service = new TranslationService(Caffeine.newBuilder().build(), provider);
        assertNull(service.translate("hello", "fr"));
    }
}
