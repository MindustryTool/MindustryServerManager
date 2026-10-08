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
import common.server.ServerConfigMessage;
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
        this.localSigningKey = localSigningKey;
    }

    public void configure(WsConfig ws) {
        ws.onConnect(context -> {
            try {
                UUID serverId = parseServerJwt(context);
                gatewayService.of(serverId).onOpen(context);
            } catch (JWTVerificationException e) {
                Log.err("Invalid token for server: " + UUID.fromString(context.header("X-SERVER-ID")));
                UUID serverId = UUID.fromString(context.header("X-SERVER-ID"));
                rewiteJwt(serverId);
                context.closeSession();
            } catch (Exception e) {
                Log.err("Error on connect", e);
                context.closeSession();
            }
        });

        ws.onMessage(context -> {
            try {
                UUID serverId = parseServerJwt(context);
                gatewayService.of(serverId).onMessage(context);
            } catch (JWTVerificationException e) {
                Log.err("Invalid token for server: " + UUID.fromString(context.header("X-SERVER-ID")));
            } catch (Exception e) {
                Log.err("Error on message", e);
            }
        });

        ws.onBinaryMessage(context -> {
            try {
                UUID serverId = parseServerJwt(context);
                gatewayService.of(serverId).onBinary(context);
            } catch (JWTVerificationException e) {
                Log.err("Invalid token for server: " + UUID.fromString(context.header("X-SERVER-ID")));
            } catch (Exception e) {
                Log.err("Error on binary message", e);
            }
        });

        ws.onClose(context -> {
            try {
                UUID serverId = parseServerJwt(context);
                gatewayService.of(serverId).onClose(context);
            } catch (JWTVerificationException e) {
                Log.err("Invalid token for server: " + UUID.fromString(context.header("X-SERVER-ID")));
                try {
                    UUID serverId = UUID.fromString(context.header("X-SERVER-ID"));
                    gatewayService.of(serverId).onClose(context);
                } catch (Exception ex) {
                    Log.err("Error on close without auth", ex);
                }
            } catch (Exception e) {
                Log.err("Error on close", e);
            }
        });

        ws.onError(context -> {
            Throwable error = context.error();
            if (error != null && error instanceof ClosedChannelException) {
                return; // Ignore closed channel exceptions
            }
            Log.err("WebSocket error", error);
        });
    }

    public UUID parseServerJwt(WsContext context) {
        String jwtToken = context.header("Authorization");

        try {
            var idString = JWT.require(Algorithm.HMAC256(localSigningKey))
                    .withIssuer("MindustryTool")
                    .build()
                    .verify(jwtToken)
                    .getSubject();

            return UUID.fromString(idString);
        } catch (JWTVerificationException e) {
            throw e;
        } catch (Exception e) {
            Log.err("Something is wrong with token", e);

            throw new RuntimeException("Something is wrong with token");
        }
    }

    public void rewiteJwt(UUID serverId) {
        ServerConfigMessage serverConfig = new ServerConfigMessage();

        try {
            Fi serverConfigFile = nodeManager.getFile(serverId, "server.json");
            if (serverConfigFile.exists()) {
                serverConfig = Utils.objectMapper.readValue(serverConfigFile.readBytes(), ServerConfigMessage.class);
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
    }

    public String generateServerJwt(UUID serverId) {
        return JWT.create()
                .withSubject(serverId.toString())
                .withIssuer("MindustryTool")
                .withExpiresAt(new Date(System.currentTimeMillis() + Duration.ofDays(365).toMillis()))
                .sign(Algorithm.HMAC256(localSigningKey));
    }
}
