package server.service;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import arc.files.Fi;
import arc.util.Log;
import dto.ServerConfigDto;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import lombok.RequiredArgsConstructor;
import server.EnvConfig;
import server.config.Const;
import server.manager.NodeManager;
import server.utils.ApiError;
import server.utils.Utils;

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
                gatewayService.of(serverId).onOpen(handler);
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
                gatewayService.of(serverId).onClose(handler);
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
        } catch (Exception e) {
            ServerConfigDto serverConfig = new ServerConfigDto();
            try {
                Fi serverConfigFile = nodeManager.getFile(serverId, "server.json");
                if (serverConfigFile.exists()) {
                    serverConfig = Utils.objectMapper.readValue(serverConfigFile.readBytes(), ServerConfigDto.class);
                }
            } catch (Exception ex) {
                Log.warn("Failed to read server.json for @, creating fresh", serverId);
            }
            serverConfig.setJwt(generateServerJwt(serverId, securityKey));
            try {
                nodeManager.writeFile(serverId, "server.json", Utils.objectMapper.writeValueAsBytes(serverConfig));
            } catch (Exception ex) {
                Log.err("Failed to write server.json for " + serverId, ex);
            }

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
