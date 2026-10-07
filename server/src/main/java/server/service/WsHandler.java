package server.service;

import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;

import arc.files.Fi;
import arc.util.Log;
import dto.ServerConfigDto;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import server.manager.NodeManager;
import server.utils.Utils;

public class WsHandler {
    private final GatewayService gatewayService;
    private final NodeManager nodeManager;
    private final String localSigningKey;

    public WsHandler(GatewayService gatewayService, NodeManager nodeManager, String localSigningKey) {
        this.gatewayService = Objects.requireNonNull(gatewayService, "gatewayService");
        this.nodeManager = Objects.requireNonNull(nodeManager, "nodeManager");
        String key = localSigningKey == null ? null : localSigningKey.trim();
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Missing required gateway signing key");
        }
        this.localSigningKey = key;
    }

    public void configure(WsConfig ws) {
        ws.onConnect(handler -> {
            try {
                UUID serverId = parseServerJwt(handler);
                gatewayService.of(serverId).onOpen(handler);
            } catch (JWTVerificationException e) {
                Log.err("Invalid token for server: " + UUID.fromString(handler.header("X-SERVER-ID")));
                handler.closeSession();
            } catch (Exception e) {
                Log.err("Error on connect", e);
                handler.closeSession();
            }
        });

        ws.onMessage(handler -> {
            try {
                UUID serverId = parseServerJwt(handler);
                gatewayService.of(serverId).onMessage(handler);
            } catch (JWTVerificationException e) {
                Log.err("Invalid token for server: " + UUID.fromString(handler.header("X-SERVER-ID")));
            } catch (Exception e) {
                Log.err("Error on message", e);
            }
        });

        ws.onBinaryMessage(handler -> {
            try {
                UUID serverId = parseServerJwt(handler);
                gatewayService.of(serverId).onBinary(handler);
            } catch (JWTVerificationException e) {
                Log.err("Invalid token for server: " + UUID.fromString(handler.header("X-SERVER-ID")));
            } catch (Exception e) {
                Log.err("Error on binary message", e);
            }
        });

        ws.onClose(handler -> {
            try {
                UUID serverId = parseServerJwt(handler);
                gatewayService.of(serverId).onClose(handler);
            } catch (JWTVerificationException e) {
                Log.err("Invalid token for server: " + UUID.fromString(handler.header("X-SERVER-ID")));
                try {
                    UUID serverId = UUID.fromString(handler.header("X-SERVER-ID"));
                    gatewayService.of(serverId).onClose(handler);
                } catch (Exception ex) {
                    Log.err("Error on close without auth", ex);
                }
            } catch (Exception e) {
                Log.err("Error on close", e);
            }
        });

        ws.onError(handler -> {
            Throwable error = handler.error();
            if (error != null && error instanceof ClosedChannelException) {
                return; // Ignore closed channel exceptions
            }
            Log.err("WebSocket error", error);
        });
    }

    public UUID parseServerJwt(WsContext context) {
        String jwtToken = context.header("Authorization");
        UUID serverId = UUID.fromString(context.header("X-SERVER-ID"));

        try {
            var idString = JWT.require(Algorithm.HMAC256(localSigningKey))
                    .withIssuer("MindustryTool")
                    .build()
                    .verify(jwtToken)
                    .getSubject();

            return UUID.fromString(idString);
        } catch (JWTVerificationException e) {
            ServerConfigDto serverConfig = new ServerConfigDto();
            try {
                Fi serverConfigFile = nodeManager.getFile(serverId, "server.json");
                if (serverConfigFile.exists()) {
                    serverConfig = Utils.objectMapper.readValue(serverConfigFile.readBytes(), ServerConfigDto.class);
                }
            } catch (Exception ex) {
                Log.warn("Failed to read server.json for @, creating fresh", serverId);
            }
            serverConfig.setJwt(generateServerJwt(serverId));
            try {
                nodeManager.writeFile(serverId, "server.json", Utils.objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(serverConfig));
            } catch (Exception ex) {
                Log.err("Failed to write server.json for " + serverId, ex);
            }

            throw e;
        } catch (Exception e) {

            Log.err("Something is wrong with token", e);

            throw new RuntimeException("Something is wrong with token");
        }
    }

    public String generateServerJwt(UUID serverId) {
        return JWT.create()
                .withSubject(serverId.toString())
                .withIssuer("MindustryTool")
                .withExpiresAt(new Date(System.currentTimeMillis() + Duration.ofDays(365).toMillis()))
                .sign(Algorithm.HMAC256(localSigningKey));
    }
}
