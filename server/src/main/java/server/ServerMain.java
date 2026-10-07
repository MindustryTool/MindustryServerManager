package server;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import server.manager.DockerNodeManager;
import server.manager.NodeManager;
import server.service.*;
import server.service.translation.TranslationService;
import server.utils.ApiError;
import arc.util.Log;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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
        ApiService apiService = new ApiService();
        PluginBundleService pluginBundle = PluginBundleService.loadFromImage();
        GatewayService gatewayService = new GatewayService(eventBus, envConfig, nodeManager, new TranslationService(), pluginBundle);
        WsHandler wsHandler = new WsHandler(gatewayService, nodeManager, envConfig.gatewaySigningKey());
        ServerService serverService = new ServerService(gatewayService, nodeManager, eventBus, apiService, wsHandler, pluginBundle);
        BackendGatewayConfig gatewayConfig = BackendGatewayConfig.fromEnv(envConfig);
        BackendGateway backendGateway = new BackendGateway(gatewayConfig, nodeManager, serverService,
                gatewayService, eventBus);

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.router.contextPath = "/";
            config.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setMaxTextMessageSize(50 * 1024 * 1024);
                factory.setMaxBinaryMessageSize(50 * 1024 * 1024);
            });
            config.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            }));
        });

        app.exception(ApiError.class, (e, ctx) -> {
            ctx.status(e.status);
            ctx.json(Map.of(
                    "error", e.getMessage(),
                    "message", e.getMessage() + "",
                    "url", ctx.path() + "",
                    "source", "Server Manager"//
            ));
        });

        app.exception(Exception.class, (e, ctx) -> {
            Log.err(e);
            ctx.status(500);
            ctx.json(Map.of(
                    "error", "Internal Server Error: " + e.getMessage(),
                    "message", e.getMessage() + "",
                    "url", ctx.path() + "",
                    "source", "Server Manager"//
            ));
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

        app.get("/", ctx -> ctx.result("pong"));

        app.ws("/gateway", ws -> {
            wsHandler.configure(ws);
        });

        app.wsException(Exception.class, (e, ctx) -> {
            Log.err("Error on WebSocket", e);
        });

        app.start(8088);
        Log.info("Server Manager started on port 8088");

        backendGateway.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                backendGateway.close();
            } catch (Exception e) {
                Log.err("Error closing backend gateway connection", e);
            }
            try {
                app.stop();
            } catch (Exception e) {
                Log.err("Error stopping Javalin", e);
            }
        }, "server-main-shutdown"));
    }
}
