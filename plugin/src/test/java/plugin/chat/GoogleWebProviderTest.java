package plugin.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GoogleWebProviderTest {

    @Test
    public void testCooldownHandling() {
        GoogleWebProvider provider = new GoogleWebProvider(null) {
            @Override
            protected boolean isGatewayConnected() {
                return true;
            }
        };

        assertTrue(provider.isAvailable());
        assertFalse(provider.isCooldownActive());
        assertEquals(0, provider.getFailureCount());

        provider.triggerCooldown();
        assertFalse(provider.isAvailable());
        assertTrue(provider.isCooldownActive());
        assertEquals(1, provider.getFailureCount());

        provider.triggerCooldown();
        assertEquals(2, provider.getFailureCount());

        provider.resetCooldown();
        assertTrue(provider.isAvailable());
        assertFalse(provider.isCooldownActive());
        assertEquals(0, provider.getFailureCount());
    }

    @Test
    public void testTranslateWithoutGatewayThrowsException() {
        GoogleWebProvider provider = new GoogleWebProvider(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> provider.translate("hello", "vi"));
        assertTrue(ex.getMessage().contains("disconnected"));
    }
}
