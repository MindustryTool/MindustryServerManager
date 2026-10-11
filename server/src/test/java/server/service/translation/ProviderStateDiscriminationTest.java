package server.service.translation;

import org.junit.jupiter.api.Test;

import common.translation.TranslationResponse;

import static org.junit.jupiter.api.Assertions.*;

public class ProviderStateDiscriminationTest {

    @Test
    public void testTemporaryFailureTriggersCooldown() throws Exception {
        ProviderState state = new ProviderState();
        assertThrows(TranslationException.Temporary.class,
                () -> state.execute("p", () -> {
                    throw new TranslationException.Temporary("rate limited");
                }));
        assertEquals(1, state.getConsecutiveFailures());
        assertFalse(state.isAvailable());
        assertEquals(1, state.getTotalFailures());
    }

    @Test
    public void testPermanentFailureSkipsCooldown() throws Exception {
        ProviderState state = new ProviderState();
        assertThrows(TranslationException.Permanent.class,
                () -> state.execute("p", () -> {
                    throw new TranslationException.Permanent("bad request");
                }));
        assertEquals(0, state.getConsecutiveFailures());
        assertTrue(state.isAvailable());
        assertEquals(1, state.getTotalFailures());
        assertEquals(0.0, state.getRecentSuccessRate(), 0.001);
    }

    @Test
    public void testNullResultDoesNotTriggerCooldown() throws Exception {
        ProviderState state = new ProviderState();
        assertNull(state.execute("p", () -> null));
        assertEquals(0, state.getConsecutiveFailures());
        assertTrue(state.isAvailable());
        assertEquals(1, state.getTotalFailures());
    }

    @Test
    public void testHttpStatusMapping() {
        assertInstanceOf(TranslationException.Temporary.class,
                TranslationException.fromHttpStatus("p", 429, "slow down"));
        assertInstanceOf(TranslationException.Temporary.class,
                TranslationException.fromHttpStatus("p", 503, "down"));
        assertInstanceOf(TranslationException.Permanent.class,
                TranslationException.fromHttpStatus("p", 400, "bad"));
        assertInstanceOf(TranslationException.Permanent.class,
                TranslationException.fromHttpStatus("p", 404, "missing"));
    }

    @Test
    public void testSuccessAfterPermanentFailure() throws Exception {
        ProviderState state = new ProviderState();
        try {
            state.execute("p", () -> {
                throw new TranslationException.Permanent("bad");
            });
        } catch (TranslationException.Permanent ignored) {
        }
        TranslationResponse ok = state.execute("p", () -> new TranslationResponse("hi", "en"));
        assertNotNull(ok);
        assertEquals(1, state.getTotalSuccesses());
        assertTrue(state.isAvailable());
    }
}
