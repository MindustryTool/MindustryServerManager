package server.manager;

import java.util.List;
import java.util.UUID;
import arc.files.Fi;
import server.types.data.NodeUsage;
import server.types.data.ServerConfig;
import server.types.data.ServerState;
import server.types.data.ServerMisMatch;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import dto.MapDto;
import dto.ModDto;
import dto.ServerStateDto;
import events.LogEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface NodeManager {

    public abstract Flux<LogEvent> create(ServerConfig config);

    public abstract Flux<ServerState> list();

    public abstract Mono<Void> remove(UUID id);

    public abstract Flux<ServerMisMatch> getMismatch(//
            UUID id, //
            ServerConfig config, //
            ServerStateDto state,
            List<ModDto> mods//
    );

    public abstract Flux<NodeUsage> getNodeUsage(UUID serverId);

    public abstract Flux<ManagerMapDto> getManagerMaps();

    public abstract Flux<ManagerModDto> getManagerMods();

    public abstract Flux<MapDto> getMaps(UUID serverId);

    public abstract Flux<ModDto> getMods(UUID serverId);

    public abstract Object getFiles(UUID serverId, String path);

    public abstract Mono<Fi> getFile(UUID serverId, String path);

    public abstract Mono<Void> writeFile(UUID serverId, String path, byte[] data);

    public abstract Mono<Boolean> deleteFile(UUID serverId, String path);
}
