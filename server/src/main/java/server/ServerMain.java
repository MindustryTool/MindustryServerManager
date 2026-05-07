package server;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.http.UploadedFile;
import io.javalin.json.JavalinJackson;
import server.manager.DockerNodeManager;
import server.manager.NodeManager;
import server.service.*;
import server.utils.ApiError;
import server.utils.Utils;
import arc.files.Fi;
import arc.util.Log;
import dto.LoginDto;
import dto.ServerConfig;
import enums.NodeRemoveReason;
import server.types.data.ServerManagerJwt;
import server.types.request.SendCommandBody;
import events.BaseEvent;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class ServerMain {

    public static void main(String[] args) {
        EnvConfig envConfig = EnvConfig.load();
        EventBus eventBus = new EventBus();

        // Initialize Docker Client
        DefaultDockerClientConfig dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient dockerHttpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

        DockerClient dockerClient = DockerClientImpl.getInstance(dockerConfig, dockerHttpClient);

        NodeManager nodeManager = new DockerNodeManager(dockerClient, envConfig, eventBus);
        ApiService apiService = new ApiService(envConfig);
        GatewayService gatewayService = new GatewayService(eventBus, envConfig, nodeManager);
        WsHandler wsHandler = new WsHandler(envConfig, gatewayService, nodeManager);
        ServerService serverService = new ServerService(gatewayService, nodeManager, eventBus, apiService, wsHandler,
                envConfig);

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.router.contextPath = "/";
            config.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            }));
        });

        app.exception(ApiError.class, (e, ctx) -> {
            ctx.status(e.status);
            ctx.json(Map.of("error", e.getMessage()));
        });

        app.exception(Exception.class, (e, ctx) -> {
            Log.err(e);
            ctx.status(500);
            ctx.json(Map.of("error", "Internal Server Error: " + e.getMessage()));
        });

        app.before(ctx -> ctx.attribute("start", Instant.now()));
        app.after(ctx -> {
            Instant start = ctx.attribute("start");
            if (start == null) {
                return;
            }
            long duration = Duration.between(start, Instant.now()).toMillis();
            String message = "[%dms] [%d] %s %s".formatted(duration, ctx.status().getCode(), ctx.method(), ctx.path());
            if (ctx.status().getCode() >= 500) {
                Log.err(message);
            } else if (ctx.status().getCode() >= 400) {
                Log.warn(message);
            } else {
                Log.info(message);
            }
        });

        app.before(ctx -> {
            String uri = ctx.path();
            if (uri.equals("/")) {
                return;
            }

            String securityKey = envConfig.serverConfig().securityKey();
            if (securityKey == null) {
                throw ApiError.forbidden("Security token is not set");
            }

            String authHeader = ctx.header("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw ApiError.unauthorized();
            }

            String token = authHeader.substring(7);
            try {
                var claims = JWT.require(Algorithm.HMAC256(securityKey))
                        .withIssuer("MindustryTool")
                        .build()
                        .verify(token)
                        .getClaims();

                ServerManagerJwt data = new ServerManagerJwt()
                        .setId(UUID.fromString(claims.get("id").asString()))
                        .setUserId(UUID.fromString(claims.get("userId").asString()));

                ctx.attribute("jwt", data);
            } catch (Exception e) {
                throw ApiError.forbidden("Invalid token");
            }
        });

        app.get("/", ctx -> ctx.result("pong"));

        app.sse("/api/v2/events", client -> {
            Consumer<BaseEvent> listener = event -> client.sendEvent(Utils.toJsonString(event));
            serverService.addEventListener(listener);
            client.keepAlive();
            client.onClose(() -> {
                serverService.removeEventListener(listener);
                Log.info("Backend event stream disconnected");
            });

            Log.info("Backend event stream connected");
        });

        app.get("/api/v2/servers/{id}/files", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            String path = URLDecoder.decode(ctx.queryParam("path"), StandardCharsets.UTF_8);
            ctx.json(serverService.getFiles(id, path));
        });

        app.get("/api/v2/servers/{id}/files/exists", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            String path = URLDecoder.decode(ctx.queryParam("path"), StandardCharsets.UTF_8);
            ctx.json(serverService.isFileExists(id, path));
        });

        app.get("/api/v2/servers/{id}/files/download", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            String path = URLDecoder.decode(ctx.pathParam("path"), StandardCharsets.UTF_8);
            Fi file = nodeManager.getFile(id, path);

            ctx.header("Content-Type", "application/octet-stream");
            ctx.header("Content-Disposition", "attachment; filename=\"" + file.name() + "\"");
            ctx.header("Content-Length", String.valueOf(file.length()));

            ctx.result(file.readBytes());
        });

        app.post("/api/v2/servers/{id}/files", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            String path = ctx.formParam("path");
            UploadedFile file = ctx.uploadedFile("file");
            byte[] data = file != null ? file.content().readAllBytes() : new byte[0];
            serverService.writeFile(id, path, data, file != null ? file.filename() : null);
        });

        app.post("/api/v2/servers/{id}/folders", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            String path = URLDecoder.decode(ctx.queryParam("path"), StandardCharsets.UTF_8);
            ctx.json(serverService.createFolder(id, path));
        });

        app.delete("/api/v2/servers/{id}/files", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            String path = URLDecoder.decode(ctx.queryParam("path"), StandardCharsets.UTF_8);
            ctx.json(serverService.deleteFile(id, path));
        });

        app.get("/api/v2/servers/{id}/commands", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            var commands = gatewayService.of(id).server().getCommands().get(10, TimeUnit.SECONDS);
            ctx.json(commands);
        });

        app.post("/api/v2/servers/{id}/commands", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            SendCommandBody body = ctx.bodyAsClass(SendCommandBody.class);
            gatewayService.of(id).server().sendCommand(body.getCommand()).get(10, TimeUnit.SECONDS);
            ctx.result();
        });

        app.post("/api/v2/servers/{id}/host", ctx -> {
            ServerConfig request = ctx.bodyAsClass(ServerConfig.class);
            serverService.host(request);
            ctx.result();
        });

        app.get("/api/v2/servers/{id}/players", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            ctx.json(serverService.getPlayers(id));
        });

        app.post("/api/v2/servers/{id}/players/{uuid}", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            String uuid = ctx.pathParam("uuid");
            LoginDto payload = ctx.bodyAsClass(LoginDto.class);
            serverService.updatePlayer(id, uuid, payload);
            ctx.result();
        });

        app.get("/api/v2/servers/{id}/state", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            ctx.json(serverService.state(id));
        });

        app.sse("/api/v2/servers/{id}/usage", client -> {
            UUID id = UUID.fromString(client.ctx().pathParam("id"));

            var closeable = serverService.getUsage(id, usage -> {
                client.sendEvent(Utils.toJsonString(usage));
            }, err -> {
                client.sendEvent("error", err.getMessage());
                client.close();
            });

            if (client.terminated()) {
                try {
                    closeable.close();
                } catch (IOException e1) {
                    Log.err("Error closing usage stream", e1);
                }
            }

            client.onClose(() -> {
                try {
                    closeable.close();
                } catch (Exception e) {
                    Log.err("Error closing usage stream", e);
                }
            });
        });

        app.delete("/api/v2/servers/{id}/remove", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            serverService.remove(id, NodeRemoveReason.USER_REQUEST);
            ctx.status(204);
        });

        app.post("/api/v2/servers/{id}/pause", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            ctx.json(serverService.pause(id));
        });

        app.get("/api/v2/servers/{id}/image", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            ctx.contentType(ContentType.IMAGE_PNG);
            ctx.result(serverService.getImage(id));
        });

        app.get("/api/v2/mods", ctx -> ctx.json(serverService.getManagerMods()));
        app.get("/api/v2/maps", ctx -> ctx.json(serverService.getManagerMaps()));

        app.get("/api/v2/servers/{id}/mods", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            ctx.json(serverService.getMods(id));
        });
        app.get("/api/v2/servers/{id}/maps", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            ctx.json(serverService.getMaps(id));
        });

        app.get("/api/v2/servers/{id}/kicks", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            ctx.json(gatewayService.of(id).server().getKickedIps().get(10, TimeUnit.SECONDS));
        });

        app.get("/api/v2/servers/{id}/json", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            ctx.json(gatewayService.of(id).server().getJson().get(10, TimeUnit.SECONDS));
        });

        app.get("/api/v2/servers/{id}/player-infos", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            Integer page = ctx.queryParamAsClass("page", Integer.class)
                    .check((v) -> v >= 0, "Page must be non-negative").getOrDefault(0);
            Integer size = ctx.queryParamAsClass("size", Integer.class)
                    .check((v) -> v >= 1 && v <= 100, "Size must be between 1 and 100").getOrDefault(10);
            Boolean banned = ctx.queryParamAsClass("banned", Boolean.class).getOrDefault(null);
            String filter = ctx.queryParam("filter");

            ctx.json(gatewayService.of(id).server().getPlayersInfo(page, size, banned, filter).get(10,
                    TimeUnit.SECONDS));
        });

        app.post("/api/v2/servers/{id}/chat", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            gatewayService.of(id).server().sendChat(ctx.bodyAsClass(JsonNode.class));
            ctx.result();
        });

        app.post("/api/v2/servers/{id}/mismatch", ctx -> {
            UUID id = UUID.fromString(ctx.pathParam("id"));
            ServerConfig config = ctx.bodyAsClass(ServerConfig.class);
            ctx.json(serverService.getMismatch(id, config));
        });

        app.ws("/gateway", ws -> {
            wsHandler.configure(ws);
        });

        app.wsException(Exception.class, (e, ctx) -> {
            Log.err("Error on WebSocket", e);
        });

        app.start(8088);
        Log.info("Server Manager started on port 8088");

        apiService.requestBackendConnection();
    }
}
