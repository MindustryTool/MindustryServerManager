package server.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import arc.files.Fi;
import server.types.data.NodeUsage;
import server.types.data.ServerConfig;
import server.types.data.ServerState;
import server.types.data.ServerMisMatch;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import dto.MapDto;
import dto.ModDto;
import dto.ServerFileDto;
import dto.StatsDto;
import events.BaseEvent;
import events.LogEvent;
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

    public abstract Flux<ServerMisMatch> getMismatch(UUID id, ServerConfig config, StatsDto stats,
            List<ModDto> mods);

    public abstract Flux<NodeUsage> getNodeUsage(UUID serverId);

    public abstract Flux<ManagerMapDto> getManagerMaps();

    public abstract Flux<ManagerModDto> getManagerMods();

    public abstract Flux<MapDto> getMaps(UUID serverId);

    public abstract Flux<ModDto> getMods(UUID serverId);

    public abstract Flux<ServerFileDto> getFiles(UUID serverId, String path);

    public abstract Mono<Fi> getFile(UUID serverId, String path);

    public abstract Mono<Void> writeFile(UUID serverId, String path, byte[] data);

    public abstract Mono<Boolean> deleteFile(UUID serverId, String path);
}
