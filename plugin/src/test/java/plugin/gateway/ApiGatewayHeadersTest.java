package plugin.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

import gateway.client.WsClient;
import gateway.rpc.RpcChannel;
import plugin.Cfg;

/**
 * Southbound handshake headers (task 4.1, unit half): the plugin dials with a
 * raw JWT plus {@code X-SERVER-ID} resolved fresh on every attempt, and dials
 * without {@code Authorization} when no JWT is available so the manager can
 * provision via {@code Token expired}. Reconnect/backoff behavior itself is
 * covered by {@code WsClient} gateway tests; live provisioning smoke is
 * task 4.3.
 */
class ApiGatewayHeadersTest {

    @Test
    void sendsRawJwtPlusServerId() {
        Map<String, String> headers = ApiGateway.gatewayHeaders("jwt-abc", "sid-1");

        assertEquals("jwt-abc", headers.get("Authorization"));
        assertEquals("sid-1", headers.get("X-SERVER-ID"));
        assertEquals(2, headers.size());
    }

    @Test
    void missingJwtDialsWithoutAuthorization() {
        Map<String, String> headers = ApiGateway.gatewayHeaders(null, "sid-1");

        assertFalse(headers.containsKey("Authorization"));
        assertEquals("sid-1", headers.get("X-SERVER-ID"));
    }

    @Test
    void blankValuesOmitted() {
        assertTrue(ApiGateway.gatewayHeaders("  ", "sid-1").containsKey("X-SERVER-ID"));
        assertFalse(ApiGateway.gatewayHeaders("  ", "sid-1").containsKey("Authorization"));
        assertTrue(ApiGateway.gatewayHeaders(null, null).isEmpty());
    }

    @Test
    void explicitGatewayUrlWins() {
        assertEquals("wss://custom:9090/gateway",
                Cfg.resolveGatewayUrl("wss://custom:9090/gateway", true));
        assertEquals("wss://custom:9090/gateway",
                Cfg.resolveGatewayUrl("wss://custom:9090/gateway", false));
    }

    @Test
    void blankGatewayUrlFallsBackByDevMode() {
        assertEquals("ws://server-manager:8088/gateway", Cfg.resolveGatewayUrl(null, true));
        assertEquals("ws://server-manager:8088/gateway", Cfg.resolveGatewayUrl("  ", true));
        assertEquals("ws://server.mindustry-tool.com:8089/gateway", Cfg.resolveGatewayUrl(null, false));
        assertEquals("ws://server.mindustry-tool.com:8089/gateway", Cfg.resolveGatewayUrl("", false));
    }

    @Test
    void nonWsSchemeRejectedByClientBuilder() {
        String resolved = Cfg.resolveGatewayUrl("http://manager:8088/gateway", true);

        assertThrows(IllegalArgumentException.class, () -> WsClient
                .builder(URI.create(resolved), RpcChannel.create())
                .build());
    }
}
