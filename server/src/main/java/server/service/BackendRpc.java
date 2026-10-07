package server.service;

import java.io.Closeable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import java.util.Objects;

import arc.files.Fi;
import arc.util.Log;
import dto.LoginDto;
import dto.ServerConfig;
import enums.NodeRemoveReason;
import gateway.rpc.StreamReply;
import gateway.rpc.SubscriptionRequest;
import gateway.rpc.WsRpcChannel;
import gateway.stream.FileChunkStreamer;
import lombok.RequiredArgsConstructor;
import server.manager.NodeManager;
import server.utils.ApiError;
import server.utils.Utils;

@RequiredArgsConstructor
public class BackendRpc {

    private static final long RPC_TIMEOUT_SECONDS = 30;

    private final ServerService serverService;
    private final GatewayService gatewayService;
    private final NodeManager nodeManager;

    public void attach(WsRpcChannel channel) {
        channel.registerHandler("host-server", ServerConfig.class, config -> {
            serverService.host(config);
            return null;
        });
        channel.registerHandler("remove-server", ServerRef.class, ref -> {
            serverService.remove(ref.serverId(), NodeRemoveReason.USER_REQUEST);
            return null;
        });
        channel.registerHandler("pause", ServerRef.class, ref -> serverService.pause(ref.serverId()));
        channel.registerHandler("send-command", SendCommandRequest.class, req -> {
            await(gatewayService.of(req.serverId()).server().sendCommand(req.command()), "send-command");
            return null;
        });
        channel.registerHandler("get-state", ServerRef.class, ref -> serverService.state(ref.serverId()));
        channel.registerHandler("get-players", ServerRef.class, ref -> serverService.getPlayers(ref.serverId()));
        channel.registerHandler("update-player", UpdatePlayerRequest.class, req -> {
            serverService.updatePlayer(req.serverId(), req.uuid(), req.player());
            return null;
        });
        channel.registerHandler("get-files", FilesRequest.class,
                req -> serverService.getFiles(req.serverId(), req.path()));
        channel.registerHandler("delete-file", FilesRequest.class,
                req -> serverService.deleteFile(req.serverId(), req.path()));
        channel.registerHandler("create-folder", FilesRequest.class,
                req -> serverService.createFolder(req.serverId(), req.path()));
        channel.registerHandler("get-maps", ServerRef.class, ref -> serverService.getMaps(ref.serverId()));
        channel.registerHandler("get-mods", ServerRef.class, ref -> serverService.getMods(ref.serverId()));
        channel.registerHandler("get-manager-maps", Void.class, ignored -> serverService.getManagerMaps());
        channel.registerHandler("get-manager-mods", Void.class, ignored -> serverService.getManagerMods());
        channel.registerHandler("get-mismatch", MismatchRequest.class,
                req -> serverService.getMismatch(req.serverId(), req.config()));
        channel.registerHandler("get-commands", ServerRef.class,
                ref -> await(gatewayService.of(ref.serverId()).server().getCommands(), "get-commands"));
        channel.registerHandler("get-image", ServerRef.class, ref -> serverService.getImage(ref.serverId()));
        channel.registerHandler("get-player-infos", PlayerInfoRequest.class,
                req -> serverService.getPlayersInfo(req.serverId(), req.page(), req.size(), req.banned(),
                        req.filter()));
        channel.registerHandler("get-recent-players", ServerRef.class,
                ref -> serverService.getRecentPlayers(ref.serverId()));
        channel.registerHandler("get-kicked-ips", ServerRef.class, ref -> serverService.getKickedIps(ref.serverId()));
        channel.registerHandler("delete-kicked-ip", DeleteKickedIpRequest.class,
                req -> serverService.deleteKickedIp(req.serverId(), req.ip()));
        channel.registerHandler("send-chat", ChatRequest.class, req -> {
            await(gatewayService.of(req.serverId()).server().sendChat(Utils.getObjectMapper()
                    .valueToTree(req.message())), "send-chat");
            return null;
        });
        channel.registerHandler("download-file", DownloadRequest.class, this::download);
        channel.registerStreamHandler("file-upload", UploadRequest.class, Map.class, (meta, bytes) -> {
            if (meta == null) {
                throw new ApiError(400, "file-upload requires metadata");
            }
            nodeManager.getFile(meta.serverId(), meta.path());
            nodeManager.writeFile(meta.serverId(), meta.path(), bytes);
            Log.info("File upload complete: " + meta.path() + " (" + bytes.length + " bytes)");
            return Map.of("bytes", bytes.length, "sha256", FileChunkStreamer.sha256Hex(bytes));
        });
        channel.registerEventListener("get-usage", ServerRef.class, this::listenUsage);

        Log.info("Backend RPC handlers registered");
    }

    /** Answer a download with a reply stream; the request resolves with the file bytes. */
    public StreamReply download(DownloadRequest request) {
        if (request == null) {
            throw new ApiError(400, "download-file requires a payload");
        }
        UUID serverId = request.serverId();
        String path = request.path();
        Fi file = nodeManager.getFile(serverId, path);
        if (!file.exists()) {
            throw new ApiError(404, "File not found: " + path);
        }
        if (file.isDirectory()) {
            throw new ApiError(400, "Path is a directory: " + path);
        }

        byte[] data = file.readBytes();
        return new StreamReply(data, Map.of("fileName", file.name(), "size", data.length));
    }

    private CompletableFuture<Void> listenUsage(SubscriptionRequest<ServerRef> req) {
        var handle = req.handle();
        var params = req.params();
        if (params == null) {
            return CompletableFuture.failedFuture(new ApiError(400, "get-usage requires serverId"));
        }
        try {
            Closeable stream = serverService.getUsage(params.serverId(), usage -> {
                if (!handle.isClosed()) {
                    handle.push(usage);
                }
            }, err -> {
                if (!handle.isClosed()) {
                    handle.fail(shortReason(err));
                }
            });
            handle.onClose(() -> closeQuietly(stream));
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static <T> T await(CompletableFuture<T> future, String op) {
        try {
            return future.get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ApiError(503, "Backend RPC '" + op + "' failed", e);
        }
    }

    private static String shortReason(Throwable err) {
        String msg = err.getMessage();
        return msg != null ? msg : err.toString();
    }

    private static void closeQuietly(Closeable stream) {
        try {
            stream.close();
        } catch (Exception e) {
            Log.warn("Failed to close usage stream: " + e.getMessage());
        }
    }

    public record UploadRequest(
            UUID serverId,
            String path) {

        public UploadRequest {
            serverId = Objects.requireNonNull(serverId, "UploadRequest.serverId");
            path = Objects.requireNonNull(path, "UploadRequest.path");
        }
    }

    public record ServerRef(UUID serverId) {
        public ServerRef {
            serverId = Objects.requireNonNull(serverId, "ServerRef.serverId");
        }
    }

    public record SendCommandRequest(
            UUID serverId,
            String[] command) {

        public SendCommandRequest {
            serverId = Objects.requireNonNull(serverId, "SendCommandRequest.serverId");
            command = Objects.requireNonNull(command, "SendCommandRequest.command");
        }
    }

    public record UpdatePlayerRequest(
            UUID serverId,
            String uuid,
            LoginDto player) {

        public UpdatePlayerRequest {
            serverId = Objects.requireNonNull(serverId, "UpdatePlayerRequest.serverId");
            uuid = Objects.requireNonNull(uuid, "UpdatePlayerRequest.uuid");
            player = Objects.requireNonNull(player, "UpdatePlayerRequest.player");
        }
    }

    public record FilesRequest(
            UUID serverId,
            String path) {

        public FilesRequest {
            serverId = Objects.requireNonNull(serverId, "FilesRequest.serverId");
            path = Objects.requireNonNull(path, "FilesRequest.path");
        }
    }

    public record MismatchRequest(
            UUID serverId,
            ServerConfig config) {

        public MismatchRequest {
            serverId = Objects.requireNonNull(serverId, "MismatchRequest.serverId");
            config = Objects.requireNonNull(config, "MismatchRequest.config");
        }
    }

    public record ChatRequest(
            UUID serverId,
            String message) {

        public ChatRequest {
            serverId = Objects.requireNonNull(serverId, "ChatRequest.serverId");
            message = Objects.requireNonNull(message, "ChatRequest.message");
        }
    }

    public record DownloadRequest(
            UUID serverId,
            String path) {

        public DownloadRequest {
            serverId = Objects.requireNonNull(serverId, "DownloadRequest.serverId");
            path = Objects.requireNonNull(path, "DownloadRequest.path");
        }
    }

    public record PlayerInfoRequest(
            UUID serverId,
            int page,
            int size,
            Boolean banned,
            String filter) {

        public PlayerInfoRequest {
            serverId = Objects.requireNonNull(serverId, "PlayerInfoRequest.serverId");
        }
    }

    public record DeleteKickedIpRequest(
            UUID serverId,
            String ip) {

        public DeleteKickedIpRequest {
            serverId = Objects.requireNonNull(serverId, "DeleteKickedIpRequest.serverId");
            ip = Objects.requireNonNull(ip, "DeleteKickedIpRequest.ip");
        }
    }
}
