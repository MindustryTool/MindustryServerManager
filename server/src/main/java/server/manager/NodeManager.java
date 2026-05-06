package server.manager;

import java.io.Closeable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import arc.files.Fi;
import server.types.data.NodeUsage;
import server.types.data.ServerState;
import server.types.data.ServerMisMatch;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import dto.MapDto;
import dto.ModDto;
import dto.ServerConfig;
import dto.ServerStateDto;
import enums.NodeRemoveReason;

public interface NodeManager {

    void create(ServerConfig config);

    List<ServerState> list();

    void remove(UUID id, NodeRemoveReason reason);

    List<ServerMisMatch> getMismatch(
            UUID id,
            ServerConfig config,
            ServerStateDto state,
            List<ModDto> mods);

    Closeable getNodeUsage(UUID serverId, Consumer<NodeUsage> onUsage, Consumer<Throwable> onError);

    List<ManagerMapDto> getManagerMaps();

    List<ManagerModDto> getManagerMods();

    List<MapDto> getMaps(UUID serverId);

    List<ModDto> getMods(UUID serverId);

    Object getFiles(UUID serverId, String path);

    Fi getFile(UUID serverId, String path);

    Fi getServerFolder();

    void writeFile(UUID serverId, String path, byte[] data);

    boolean createFolder(UUID serverId, String path);

    boolean deleteFile(UUID serverId, String path);

    boolean isRunning(UUID serverId);
}
