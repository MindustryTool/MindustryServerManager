package server.service;

import server.EnvConfig;

public record BackendGatewayConfig(String wsUrl, String accessToken) {

    public static BackendGatewayConfig fromEnv(EnvConfig env) {
        return new BackendGatewayConfig(
                env.serverConfig().backendWsUrl(),
                env.serverConfig().accessToken());
    }

    public boolean isBlank() {
        return wsUrl == null || wsUrl.isBlank() || accessToken == null || accessToken.isBlank();
    }
}
