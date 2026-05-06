package server.service;

import java.util.Date;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import io.javalin.websocket.WsConfig;
import lombok.RequiredArgsConstructor;
import server.EnvConfig;
import server.utils.ApiError;

@RequiredArgsConstructor
public class WsHandler {
    private final EnvConfig envConfig;
    private final GatewayService gatewayService;

    public void configure(WsConfig ws) {
        String securityKey = envConfig.serverConfig().securityKey();

        ws.onConnect(handler -> {
            UUID serverId = parseServerJwt(handler.header("Authorization"), securityKey);
            gatewayService.of(serverId).setSocketContext(handler);
        });

        ws.onMessage(handler -> {
            UUID serverId = parseServerJwt(handler.header("Authorization"), securityKey);
            gatewayService.of(serverId).onMessage(handler);
        });

        ws.onClose(handler -> {
            UUID serverId = parseServerJwt(handler.header("Authorization"), securityKey);
            gatewayService.of(serverId).setSocketContext(null);
        });

    }

    public UUID parseServerJwt(String jwtToken, String securityKey) {
        if (securityKey == null) {
            throw ApiError.forbidden("Security token is not set");
        }

        var claims = JWT.require(Algorithm.HMAC256(securityKey))
                .withIssuer("MindustryTool")
                .build()
                .verify(jwtToken)
                .getClaims();

        return UUID.fromString(claims.get("id").asString());
    }

    public String generateServerJwt(UUID serverId, String securityKey) {
        if (securityKey == null) {
            throw ApiError.forbidden("Security token is not set");
        }

        return JWT.create()
                .withSubject(serverId.toString())
                .withIssuer("MindustryTool")
                .withExpiresAt(new Date(System.currentTimeMillis() + 60 * 1000 * 1000))
                .sign(Algorithm.HMAC256(securityKey));
    }
}
