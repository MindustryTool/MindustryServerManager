package server.manager;

import java.io.Closeable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import arc.files.Fi;
import server.types.data.NodeUsage;
import server.types.data.ServerState;
import server.types.data.ServerMisMatch;
import common.content.ManagerMap;
import common.content.ManagerMod;
import common.content.MapMetadata;
import common.content.Mod;
import common.server.ServerConfig;
import common.server.ServerSnapshot;
import common.network.NodeRemoveReason;

public interface NodeManager {

    List<ServerState> list();

    void create(ServerConfig config);

    boolean remove(UUID id, NodeRemoveReason reason);

    List<ServerMisMatch> getMismatch(
            UUID id,
            ServerConfig config,
            ServerSnapshot state,
            List<Mod> mods);

    Closeable getNodeUsage(UUID serverId, Consumer<NodeUsage> onUsage, Consumer<Throwable> onError);

    List<ManagerMap> getManagerMaps();

    List<ManagerMod> getManagerMods();

    List<MapMetadata> getMaps(UUID serverId);

    List<Mod> getMods(UUID serverId);

    Object getFiles(UUID serverId, String path);

    Fi getFile(UUID serverId, String path);

    Fi getServerFolder();

    void writeFile(UUID serverId, String path, byte[] data);

    boolean createFolder(UUID serverId, String path);

    boolean deleteFile(UUID serverId, String path);

    boolean isRunning(UUID serverId);

    void onKilled(Consumer<UUID> onKilled);
}
