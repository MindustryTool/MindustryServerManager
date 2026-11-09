package server.service;

import arc.util.Log;
import common.event.ServerEvents.StopEvent;
import common.network.NodeRemoveReason;
import common.ratelimit.KeyedRateLimiter;
import gateway.wire.NoSessionException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import server.EnvConfig;
import server.config.Const;
import server.manager.NodeManager;
import server.service.translation.TranslationService;
import server.types.data.ServerState;
import server.utils.ApiError;
import server.utils.Utils;

public class GatewayService {

    private static final double TRANSLATION_RATE_LIMIT_BURST = 50;
    private static final double TRANSLATION_RATE_LIMIT_REFILL_PER_SECOND = 10;
    private static final Duration TRANSLATION_RATE_LIMIT_IDLE_TTL = Duration.ofMinutes(5);

    private final EventBus eventBus;
    private final EnvConfig envConfig;
    private final NodeManager nodeManager;
    private final TranslationService translationService;
    private final PluginBundleService pluginBundleService;
    private final ConcurrentHashMap<UUID, GatewayClient> clients = new ConcurrentHashMap<>();
    private final KeyedRateLimiter<UUID> translationRateLimiter = new KeyedRateLimiter<>(
            TRANSLATION_RATE_LIMIT_BURST,
            TRANSLATION_RATE_LIMIT_REFILL_PER_SECOND,
            TRANSLATION_RATE_LIMIT_IDLE_TTL);

    private final ScheduledExecutorService scheduler;

    public GatewayService(
            EventBus eventBus,
            EnvConfig envConfig,
            NodeManager nodeManager) {
        this(
                eventBus,
                envConfig,
                nodeManager,
                new TranslationService(),
                PluginBundleService.loadFromImage());
    }

    public GatewayService(
            EventBus eventBus,
            EnvConfig envConfig,
            NodeManager nodeManager,
            TranslationService translationService) {
        this(
                eventBus,
                envConfig,
                nodeManager,
                translationService,
                PluginBundleService.loadFromImage());
    }

    public GatewayService(
            EventBus eventBus,
            EnvConfig envConfig,
            NodeManager nodeManager,
            TranslationService translationService,
            PluginBundleService pluginBundleService) {
        this(eventBus, envConfig, nodeManager, translationService, pluginBundleService, Const.scheduler);
    }

    public GatewayService(
            EventBus eventBus,
            EnvConfig envConfig,
            NodeManager nodeManager,
            TranslationService translationService,
            PluginBundleService pluginBundleService,
            ScheduledExecutorService scheduler) {
        this.eventBus = eventBus;
        this.envConfig = envConfig;
        this.nodeManager = nodeManager;
        this.translationService = translationService;
        this.pluginBundleService = pluginBundleService;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");

        nodeManager.onKilled(serverId -> {
            eventBus.emit(new StopEvent(serverId, NodeRemoveReason.PROCESS_KILLED));
        });

        this.scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        sweep();
                    } catch (Exception e) {
                        Log.err("Error sweeping gateway clients", e);
                    }
                },
                15,
                15,
                TimeUnit.SECONDS);
    }

    void sweep() {
        Set<UUID> runningIds = new HashSet<>();

        for (ServerState state : nodeManager.list()) {
            if (state.running() && state.meta().isPresent()) {
                runningIds.add(state.meta().get().getConfig().getId());
            }
        }

        clients.forEach((serverId, client) -> {
            if (client.isSocketOpen()) {
                return;
            }

            if (!runningIds.contains(serverId)) {
                clients.remove(serverId);
                client.shutdown();
            }
        });
    }

    public GatewayClient of(UUID serverId) {
        return clients.computeIfAbsent(serverId, _ignore -> new GatewayClient(
                serverId, eventBus, envConfig, nodeManager,
                translationService, pluginBundleService, translationRateLimiter, scheduler));
    }

    public boolean isHosting(UUID serverId) {
        try {
            return (clients.containsKey(serverId) &&
                    nodeManager.isRunning(serverId) &&
                    of(serverId).server().isHosting().get(5, TimeUnit.SECONDS));
        } catch (Throwable e) {
            if (e instanceof TimeoutException) {
                return false;
            }

            e = Utils.unwrap(e);

            if (e instanceof NoSessionException) {
                return false;
            }

            throw ApiError.internal(e);
        }
    }

    public boolean terminate(UUID serverId, NodeRemoveReason reason) {
        var client = clients.get(serverId);

        if (client == null) {
            return nodeManager.remove(serverId, reason);
        }

        client.terminate(reason);

        return true;
    }

    public void sendSyncState() {
        for (var client : clients.values()) {
            if (client.isSocketOpen()) {
                client.server().syncState().orTimeout(2, TimeUnit.SECONDS);
            }
        }
    }
}
