package server.service;

import java.util.List;
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
    public void testRoundRobinDistributionBetweenSameTierProviders() {
        AtomicInteger p1Calls = new AtomicInteger(0);
        AtomicInteger p2Calls = new AtomicInteger(0);

        TranslationProvider p1 = new TranslationProvider() {
            @Override
            public String name() {
                return "p1";
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                p1Calls.incrementAndGet();
                return new TranslationResponseDto("P1: " + text, "en");
            }
        };

        TranslationProvider p2 = new TranslationProvider() {
            @Override
            public String name() {
                return "p2";
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                p2Calls.incrementAndGet();
                return new TranslationResponseDto("P2: " + text, "en");
            }
        };

        TranslationService service = new TranslationService(Caffeine.newBuilder().build());
        service.registerProvider(0, 10, p1);
        service.registerProvider(0, 20, p2);

        TranslationResponseDto res1 = service.translate("msg1", "vi");
        TranslationResponseDto res2 = service.translate("msg2", "vi");
        TranslationResponseDto res3 = service.translate("msg3", "vi");
        TranslationResponseDto res4 = service.translate("msg4", "vi");

        assertEquals(2, p1Calls.get(), "P1 should be called twice in round-robin");
        assertEquals(2, p2Calls.get(), "P2 should be called twice in round-robin");
        assertEquals("P1: msg1", res1.getTranslatedText());
        assertEquals("P2: msg2", res2.getTranslatedText());
        assertEquals("P1: msg3", res3.getTranslatedText());
        assertEquals("P2: msg4", res4.getTranslatedText());
    }

    @Test
    public void testCooldownProviderSkippedInRoundRobinWithinTier() {
        AtomicInteger p1Calls = new AtomicInteger(0);
        AtomicInteger p2Calls = new AtomicInteger(0);

        TranslationProvider coolingDownP1 = new TranslationProvider() {
            @Override
            public String name() {
                return "p1-cooling";
            }

            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                p1Calls.incrementAndGet();
                return new TranslationResponseDto("P1: " + text, "en");
            }
        };

        TranslationProvider activeP2 = new TranslationProvider() {
            @Override
            public String name() {
                return "p2-active";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                p2Calls.incrementAndGet();
                return new TranslationResponseDto("P2: " + text, "en");
            }
        };

        TranslationService service = new TranslationService(Caffeine.newBuilder().build());
        service.registerProvider(0, 10, coolingDownP1);
        service.registerProvider(0, 20, activeP2);

        TranslationResponseDto res1 = service.translate("msg1", "vi");
        TranslationResponseDto res2 = service.translate("msg2", "vi");

        assertEquals(0, p1Calls.get(), "Cooling down provider should not be called");
        assertEquals(2, p2Calls.get(), "Active provider should handle all requests");
        assertEquals("P2: msg1", res1.getTranslatedText());
        assertEquals("P2: msg2", res2.getTranslatedText());
    }

    @Test
    public void testUnavailableTierFallsBackToNextTier() {
        TranslationProvider tier0Unavailable = new TranslationProvider() {
            @Override
            public String name() {
                return "tier0-unavailable";
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

        AtomicInteger tier1Calls = new AtomicInteger(0);
        TranslationProvider tier1Available = new TranslationProvider() {
            @Override
            public String name() {
                return "tier1-available";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public TranslationResponseDto translate(String text, String targetLang) {
                tier1Calls.incrementAndGet();
                return new TranslationResponseDto("Tier1: " + text, "en");
            }
        };

        TranslationService service = new TranslationService(Caffeine.newBuilder().build());
        service.registerProvider(0, 10, tier0Unavailable);
        service.registerProvider(1, 10, tier1Available);

        TranslationResponseDto result = service.translate("test", "vi");
        assertNotNull(result);
        assertEquals("Tier1: test", result.getTranslatedText());
        assertEquals(1, tier1Calls.get());
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
            public TranslationResponseDto translate(String text, String targetLang) {
                backupCalls.incrementAndGet();
                return new TranslationResponseDto("Backup text", "en");
            }
        };

        TranslationService service = new TranslationService(Caffeine.newBuilder().build());
        service.registerProvider(0, 10, failingPrimary);
        service.registerProvider(0, 20, backup);

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

    @Test
    public void testDefaultProvidersContainsTier0AndTier1() {
        TranslationService service = new TranslationService();
        List<TranslationService.RegisteredProvider> registered = service.getRegisteredProviders();
        assertEquals(3, registered.size());

        assertEquals("lingva", registered.get(0).provider().name());
        assertEquals(0, registered.get(0).tier());

        assertEquals("google-web", registered.get(1).provider().name());
        assertEquals(0, registered.get(1).tier());

        assertEquals("google-web-proxy", registered.get(2).provider().name());
        assertEquals(1, registered.get(2).tier());
    }
}
