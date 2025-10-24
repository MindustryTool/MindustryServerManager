package server.manager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import server.types.data.NodeUsage;
import server.types.data.ServerConfig;
import server.types.data.ServerState;
import server.types.data.ServerMisMatch;
import server.types.event.BaseEvent;
import server.types.event.LogEvent;
import server.types.response.ModDto;
import server.types.response.StatsDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public abstract class NodeManager {

    private final ArrayList<Consumer<BaseEvent>> consumers = new ArrayList<>();

    public Runnable onEvent(Consumer<BaseEvent> consumer) {
        consumers.add(consumer);

        return () -> consumers.remove(consumer);
    };

    public <T extends BaseEvent> T fire(T event) {
        for (var consumer : consumers) {
            consumer.accept(event);
        }

        return event;
    }

    public abstract Flux<LogEvent> create(ServerConfig config);

    public abstract Flux<ServerState> list();

    public abstract Mono<Void> remove(UUID id);

    public abstract File getFile(UUID serverId, String path);

    public abstract boolean deleteFile(UUID serverId, String path);

    public abstract Flux<ServerMisMatch> getMismatch(UUID id, ServerConfig config, StatsDto stats,
            List<ModDto> mods);

    public abstract Flux<NodeUsage> getNodeUsage(UUID serverId);
}
