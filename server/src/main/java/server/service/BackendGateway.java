package server.service;

import java.net.URI;

import arc.util.Log;
import events.ServerEvents.LogEvent;
import gateway.client.WsClient;
import gateway.rpc.RpcChannel;
import lombok.Getter;
import server.config.Const;
import server.manager.NodeManager;

public class BackendGateway {

    private final BackendGatewayConfig config;
    private final NodeManager nodeManager;
    private final ServerService serverService;
    private final GatewayService gatewayService;
    private final EventBus eventBus;

    private volatile WsClient client;
    @Getter
    private volatile RpcChannel channel;
    private volatile Runnable eventUnsubscribe;

    private volatile boolean started = false;

    public BackendGateway(BackendGatewayConfig config, NodeManager nodeManager, ServerService serverService,
            GatewayService gatewayService, EventBus eventBus) {
        this.config = config;
        this.nodeManager = nodeManager;
        this.serverService = serverService;
        this.gatewayService = gatewayService;
        this.eventBus = eventBus;
    }

    public synchronized void start() {
        if (started) {
            return;
        }

        if (config == null || config.isBlank()) {
            throw new RuntimeException("[red]BACKEND_WS_URL or ACCESS_TOKEN_v2 is not configured; "
                    + "backend gateway connection suspended");
        }

        final URI uri;
        try {
            uri = URI.create(config.wsUrl());
        } catch (Exception e) {
            throw new RuntimeException(
                    "[red]Invalid BACKEND_WS_URL '" + config.wsUrl() + "'; backend gateway connection suspended", e);
        }

        RpcChannel rpcChannel = RpcChannel.withExecutor(Const.executorService);
        BackendRpc backendRpc = new BackendRpc(serverService, gatewayService, nodeManager);
        backendRpc.attach(rpcChannel);

        WsClient wsClient = WsClient.builder(uri, rpcChannel)
                .bearerToken(config.accessToken())
                .build();

        wsClient.onOpen(() -> Log.info("Connected to backend"));
        wsClient.onClose(err -> Log.warn("Backend connection lost: " + err.getMessage() + "; reconnect scheduled"));

        this.channel = rpcChannel;
        this.client = wsClient;
        this.eventUnsubscribe = eventBus.on(event -> {
            // Ignore log events related to JAVA_TOOL_OPTIONS
            if (event instanceof LogEvent logEvent && logEvent.getData().startsWith("Picked up JAVA_TOOL_OPTIONS")) {
                return;
            }
            rpcChannel.sendNotification("event", event);
        });
        this.started = true;

        Log.info("Connecting to backend gateway: " + uri);
        wsClient.connect();
    }

    public boolean isConnected() {
        WsClient wsClient = this.client;
        return wsClient != null && wsClient.isOpen();
    }

    /** Stop heartbeats, reconnects, event bridging and fail pending RPCs. */
    public synchronized void close() {
        started = false;
        Runnable unsub = this.eventUnsubscribe;
        this.eventUnsubscribe = null;
        if (unsub != null) {
            try {
                unsub.run();
            } catch (Exception e) {
                Log.warn("Failed to unsubscribe backend event bridge", e);
            }
        }
        WsClient wsClient = this.client;
        this.client = null;
        if (wsClient != null) {
            try {
                wsClient.close();
            } catch (Exception e) {
                Log.warn("Error closing backend connection", e);
            }
        }
        RpcChannel rpcChannel = this.channel;
        if (rpcChannel != null) {
            rpcChannel.shutdown();
        }
    }
}
