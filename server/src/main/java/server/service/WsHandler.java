package server.service;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.TokenExpiredException;

import arc.util.Log;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import lombok.RequiredArgsConstructor;
import server.EnvConfig;
import server.config.Const;
import server.manager.NodeManager;
import server.utils.ApiError;

@RequiredArgsConstructor
public class WsHandler {
    private final EnvConfig envConfig;
    private final GatewayService gatewayService;
    private final NodeManager nodeManager;

    public void configure(WsConfig ws) {
        String securityKey = envConfig.serverConfig().securityKey();

        ws.onConnect(handler -> {
            try {
                UUID serverId = parseServerJwt(handler, securityKey);
                gatewayService.of(serverId).setSocketContext(handler);
            } catch (Exception e) {
                Log.err("Error on connect", e);
                handler.closeSession();
            }
        });

        ws.onMessage(handler -> {
            Const.executorService.execute(() -> {
                try {
                    UUID serverId = parseServerJwt(handler, securityKey);
                    gatewayService.of(serverId).onMessage(handler);
                } catch (Exception e) {
                    Log.err("Error on message", e);
                }
            });
        });

        ws.onClose(handler -> {
            try {
                UUID serverId = parseServerJwt(handler, securityKey);
                gatewayService.of(serverId).setSocketContext(null);
            } catch (Exception e) {
                Log.err("Error on close", e);
            }
        });

        ws.onError(handler -> {
            Log.err("WebSocket error", handler.error());
        });
    }

    public UUID parseServerJwt(WsContext context, String securityKey) {
        String jwtToken = context.header("Authorization");
        UUID serverId = UUID.fromString(context.header("X-SERVER-ID"));

        if (securityKey == null) {
            throw ApiError.forbidden("Security token is not set");
        }

        try {
            var idString = JWT.require(Algorithm.HMAC256(securityKey))
                    .withIssuer("MindustryTool")
                    .build()
                    .verify(jwtToken)
                    .getSubject();

            return UUID.fromString(idString);
        } catch (TokenExpiredException e) {
            nodeManager.writeFile(serverId, "WEBSOCKET.txt", generateServerJwt(serverId, securityKey).getBytes());

            throw new RuntimeException("Token expired");
        }
    }

    public String generateServerJwt(UUID serverId, String securityKey) {
        if (securityKey == null) {
            throw ApiError.forbidden("Security token is not set");
        }

        return JWT.create()
                .withSubject(serverId.toString())
                .withIssuer("MindustryTool")
                .withExpiresAt(new Date(System.currentTimeMillis() + Duration.ofDays(3650).toMillis()))
                .sign(Algorithm.HMAC256(securityKey));
    }
}
