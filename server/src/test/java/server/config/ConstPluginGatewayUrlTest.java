package server.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConstPluginGatewayUrlTest {

    @Test
    void explicitValueWins() {
        assertEquals("wss://custom:9090/gateway",
                Const.resolvePluginGatewayUrl("wss://custom:9090/gateway", true));
        assertEquals("wss://custom:9090/gateway",
                Const.resolvePluginGatewayUrl("wss://custom:9090/gateway", false));
    }

    @Test
    void blankFallsBackByDevMode() {
        assertEquals("ws://server-manager:8088/gateway", Const.resolvePluginGatewayUrl(null, true));
        assertEquals("ws://server-manager:8088/gateway", Const.resolvePluginGatewayUrl("  ", true));
        assertEquals("ws://server.mindustry-tool.com:8089/gateway",
                Const.resolvePluginGatewayUrl(null, false));
        assertEquals("ws://server.mindustry-tool.com:8089/gateway",
                Const.resolvePluginGatewayUrl("", false));
    }

    @Test
    void defaultResolverNeverBlank() {
        String resolved = Const.resolvePluginGatewayUrl();

        assertNotNull(resolved);
        assertFalse(resolved.isBlank());
        assertTrue(resolved.endsWith("/gateway"));
    }
}
