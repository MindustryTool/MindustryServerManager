package server.service;

import java.io.Closeable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import java.util.Objects;

import arc.files.Fi;
import arc.util.Log;
import common.player.Login;
import common.server.ServerConfig;
import common.network.NodeRemoveReason;
import gateway.wire.StreamReply;
import gateway.subscription.SubscriptionRequest;
import gateway.rpc.RpcChannel;
import gateway.stream.ChunkWriter;
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

    public void attach(RpcChannel channel) {
        channel.registerHandler("host-server", ServerConfig.class, ctx -> {
            serverService.host(ctx.body());
            return null;
        });
        channel.registerHandler("remove-server", ServerRef.class, ctx -> {
            serverService.remove(ctx.body().serverId(), NodeRemoveReason.USER_REQUEST);
            return null;
        });
        channel.registerHandler("pause", ServerRef.class, ctx -> serverService.pause(ctx.body().serverId()));
        channel.registerHandler("send-command", SendCommandRequest.class, ctx -> {
            var req = ctx.body();
            await(gatewayService.of(req.serverId()).server().sendCommand(req.command()), "send-command");
            return null;
        });
        channel.registerHandler("get-state", ServerRef.class, ctx -> serverService.state(ctx.body().serverId()));
        channel.registerHandler("get-players", ServerRef.class, ctx -> serverService.getPlayers(ctx.body().serverId()));
        channel.registerHandler("update-player", UpdatePlayerRequest.class, ctx -> {
            var req = ctx.body();
            serverService.updatePlayer(req.serverId(), req.uuid(), req.player());
            return null;
        });
        channel.registerHandler("get-files", FilesRequest.class,
                ctx -> serverService.getFiles(ctx.body().serverId(), ctx.body().path()));
        channel.registerHandler("delete-file", FilesRequest.class,
                ctx -> serverService.deleteFile(ctx.body().serverId(), ctx.body().path()));
        channel.registerHandler("create-folder", FilesRequest.class,
                ctx -> serverService.createFolder(ctx.body().serverId(), ctx.body().path()));
        channel.registerHandler("get-maps", ServerRef.class, ctx -> serverService.getMaps(ctx.body().serverId()));
        channel.registerHandler("get-mods", ServerRef.class, ctx -> serverService.getMods(ctx.body().serverId()));
        channel.registerHandler("get-manager-maps", Void.class, _ctx -> serverService.getManagerMaps());
        channel.registerHandler("get-manager-mods", Void.class, _ctx -> serverService.getManagerMods());
        channel.registerHandler("get-mismatch", MismatchRequest.class,
                ctx -> serverService.getMismatch(ctx.body().serverId(), ctx.body().config()));
        channel.registerHandler("get-commands", ServerRef.class,
                ctx -> await(gatewayService.of(ctx.body().serverId()).server().getCommands(), "get-commands"));
        channel.registerHandler("get-image", ServerRef.class, ctx -> serverService.getImage(ctx.body().serverId()));
        channel.registerHandler("get-player-infos", PlayerInfoRequest.class,
                ctx -> serverService.getPlayersInfo(ctx.body().serverId(), ctx.body().page(), ctx.body().size(),
                        ctx.body().banned(), ctx.body().filter()));
        channel.registerHandler("get-recent-players", ServerRef.class,
                ctx -> serverService.getRecentPlayers(ctx.body().serverId()));
        channel.registerHandler("get-kicked-ips", ServerRef.class,
                ctx -> serverService.getKickedIps(ctx.body().serverId()));
        channel.registerHandler("delete-kicked-ip", DeleteKickedIpRequest.class,
                ctx -> serverService.deleteKickedIp(ctx.body().serverId(), ctx.body().ip()));
        channel.registerHandler("send-chat", ChatRequest.class, ctx -> {
            var req = ctx.body();
            await(gatewayService.of(req.serverId()).server().sendChat(Utils.getObjectMapper()
                    .valueToTree(req.message())), "send-chat");
            return null;
        });
        channel.registerHandler("download-file", DownloadRequest.class, ctx -> download(ctx.body()));
        channel.registerStreamHandler("file-upload", UploadRequest.class, Map.class, (meta, bytes) -> {
            if (meta == null) {
                throw new ApiError(400, "file-upload requires metadata");
            }
            nodeManager.getFile(meta.serverId(), meta.path());
            nodeManager.writeFile(meta.serverId(), meta.path(), bytes);
            Log.info("File upload complete: " + meta.path() + " (" + bytes.length + " bytes)");
            return Map.of("bytes", bytes.length, "sha256", ChunkWriter.sha256Hex(bytes));
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
            Login player) {

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
