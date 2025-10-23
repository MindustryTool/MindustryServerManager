package mindustrytool.servermanager.service;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import arc.files.Fi;
import arc.files.ZipFi;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.serialization.Json;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.Jformat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustry.core.Version;
import mindustry.io.MapIO;
import mindustry.mod.Mods.ModMeta;
import mindustrytool.servermanager.types.data.NodeUsage;
import mindustrytool.servermanager.types.data.ServerConfig;
import mindustrytool.servermanager.types.data.ServerMisMatch;
import mindustrytool.servermanager.types.event.LogEvent;
import mindustrytool.servermanager.config.Const;
import mindustrytool.servermanager.manager.NodeManager;
import mindustrytool.servermanager.service.GatewayService.GatewayClient;
import mindustrytool.servermanager.types.response.ManagerMapDto;
import mindustrytool.servermanager.types.response.ManagerModDto;
import mindustrytool.servermanager.types.response.MapDto;
import mindustrytool.servermanager.types.response.MindustryPlayerDto;
import mindustrytool.servermanager.types.response.ModDto;
import mindustrytool.servermanager.types.response.ModMetaDto;
import mindustrytool.servermanager.types.response.ServerFileDto;
import mindustrytool.servermanager.types.response.StatsDto;
import mindustrytool.servermanager.types.response.StatsDto.ServerStatus;
import mindustrytool.servermanager.utils.ApiError;
import mindustrytool.servermanager.utils.Utils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerService {

    private final GatewayService gatewayService;
    private final NodeManager nodeManager;

    private enum ServerFlag {
        KILL,
        RESTART
    }

    private final ConcurrentHashMap<UUID, EnumSet<ServerFlag>> serverFlags = new ConcurrentHashMap<>();
    private final Json json = new Json();

    private final Long MAX_FILE_SIZE = 5000000l;


    private Mono<Void> checkRunningServer(ServerConfig server, boolean shouldAutoTurnOff) {
        var serverId = server.getId();
        var flag = serverFlags.computeIfAbsent(serverId, (_ignore) -> EnumSet.noneOf(ServerFlag.class));

        if (server.isAutoTurnOff() == false) {
            // TODO: Restart when detect mismatch
            return Mono.empty();
        }

        return gatewayService.of(server.getId())//
                .getServer()//
                .getStats()
                .flatMap(stats -> {
                    boolean shouldKill = stats.getPlayers().isEmpty();

                    if (shouldKill && shouldAutoTurnOff) {
                        if (flag != null && flag.contains(ServerFlag.KILL)) {
                            var event = LogEvent.info(serverId, "Auto shut down server");
                            nodeManager.fire(event);
                            log.info(event.toString());
                            flag.remove(ServerFlag.KILL);
                            return remove(server.getId());
                        } else {
                            flag.add(ServerFlag.KILL);
                            var event = LogEvent.info(serverId, "Server has no players, flag to kill");
                            nodeManager.fire(event);
                            log.info(event.toString());
                            return Mono.empty();
                        }
                    } else {
                        if (flag.contains(ServerFlag.KILL)) {
                            var event = LogEvent.info(serverId, "Remove kill flag from server");
                            nodeManager.fire(event);
                            log.info(event.toString());
                            flag.remove(ServerFlag.KILL);
                            return Mono.empty();
                        }
                    }

                    return Mono.empty();
                })//
                .retry(2)//
                .onErrorResume(error -> {
                    var event = LogEvent.error(serverId, "Error: " + error.getMessage());
                    nodeManager.fire(event);
                    log.error(event.toString());

                    return Mono.empty();
                });
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    private void cron() {
        // var runningWithAutoTurnOff = containers.stream()//
        // .filter(container -> container.getState().equalsIgnoreCase("running"))//
        // .filter(container ->
        // readMetadataFromContainer(container).map(ServerMetadata::getInit).map(
        // ServerConfig::isAutoTurnOff).orElse(false))//
        // .collect(Collectors.toList())
        // .size();

        // var shouldAutoTurnOff = runningWithAutoTurnOff > 2;

        nodeManager.list()
                .flatMap(state -> {
                    if (state.running()) {
                        return checkRunningServer(state.meta().orElseThrow().getConfig(), true);
                    }

                    return Mono.empty();
                })
                .timeout(Duration.ofMinutes(5))
                .subscribeOn(Schedulers.boundedElastic())
                .blockLast();
    }

    public Mono<Void> remove(UUID serverId) {
        return nodeManager.remove(serverId);
    }

    public Mono<Boolean> pause(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .pause();
    }

    public Flux<LogEvent> host(ServerConfig request) {
        var serverId = request.getId();
        var serverGateway = gatewayService.of(serverId).getServer();

        Flux<LogEvent> hostFlux = serverGateway.isHosting().flatMapMany(isHosting -> {

            if (isHosting) {
                var event = LogEvent.info(serverId, "Server is hosting, do nothing");
                Log.info("Server is hosting, do nothing");
                return Flux.just(event);
            }

            String[] preHostCommand = { //
                    "config name %s".formatted(request.getName()), //
                    "config desc %s".formatted(request.getDescription()), //
                    "version"
            };

            Flux<LogEvent> sendCommandFlux = Flux.concat(
                    Mono.just(LogEvent.info(serverId, "Config server: " + Arrays.toString(preHostCommand))),
                    serverGateway//
                            .sendCommand(preHostCommand)
                            .thenReturn(LogEvent.info(serverId, "Config done")));

            Flux<LogEvent> sendHostFlux = Flux.concat(
                    Mono.just(LogEvent.info(serverId, "Host server")),
                    serverGateway.host(request).then(Mono.empty()));

            Flux<LogEvent> waitForStatusFlux = Flux.concat(
                    Mono.just(LogEvent.info(serverId, "Wait for server status")),
                    waitForHosting(serverGateway).thenReturn(LogEvent.info(serverId, "Server hosting")));

            return Flux.concat(sendCommandFlux, sendHostFlux, waitForStatusFlux);
        });

        Flux<LogEvent> createFlux = nodeManager.create(request);
        Flux<LogEvent> waitingOkFlux = Flux.concat(//
                Mono.just(LogEvent.info(serverId, "Waiting for server to start")), //
                serverGateway//
                        .ok()
                        .then(Mono.just(LogEvent.info(serverId, "Server started, waiting for hosting"))));

        return Flux.concat(//
                createFlux,
                waitingOkFlux, //
                hostFlux);
    }

    public Flux<ServerMisMatch> getMismatch(UUID serverId, ServerConfig config) {
        return Mono.zipDelayError(//
                stats(serverId), //
                getMods(serverId).collectList()//
        ).flatMapMany(zip -> {
            var stats = zip.getT1();
            var mods = zip.getT2();

            return nodeManager.getMismatch(serverId, config, stats, mods);
        });
    }

    public File getFile(UUID serverId, String path) {
        return Paths
                .get(Const.volumeFolderPath, "servers", serverId.toString(), "config",
                        URLDecoder.decode(path, StandardCharsets.UTF_8))
                .toFile();

    }

    private mindustry.maps.Map readMap(Fi file) {
        try {
            return MapIO.createMap(file, true);
        } catch (IOException e) {
            e.printStackTrace();
            return new mindustry.maps.Map(file, 0, 0, new StringMap(), true, 0, Version.build);
        }
    }

    public Flux<ManagerMapDto> getManagerMaps() {
        var folder = Paths.get(Const.volumeFolderPath, "servers").toFile();

        if (!folder.exists()) {
            return Flux.empty();
        }

        var result = new HashMap<String, Tuple2<Fi, List<UUID>>>();

        for (var serverFolder : new Fi(folder).list()) {
            var configFolder = serverFolder.child("config");

            if (!configFolder.exists()) {
                continue;
            }

            var mapFolder = configFolder.child("maps");

            if (!mapFolder.exists()) {
                continue;
            }

            for (var mapFile : mapFolder.list(file -> file.getName().endsWith(".msav"))) {
                result.computeIfAbsent(mapFile.name(), (_ignore) -> Tuples.of(mapFile, new ArrayList<>()))
                        .getT2()
                        .add(UUID.fromString(serverFolder.name()));
            }
        }
        return Flux.fromIterable(result.values()).map(entry -> {
            var map = readMap(entry.getT1());

            return new ManagerMapDto()//
                    .setName(map.name())//
                    .setFilename(map.file.name())
                    .setCustom(map.custom)
                    .setHeight(map.height)
                    .setServers(entry.getT2())
                    .setWidth(map.width);
        });
    }

    public Flux<ManagerModDto> getManagerMods() {
        var folder = Paths.get(Const.volumeFolderPath, "servers").toFile();

        if (!folder.exists()) {
            return Flux.empty();
        }

        var result = new HashMap<String, Tuple2<Fi, List<UUID>>>();

        for (var serverFolder : new Fi(folder).list()) {
            var configFolder = serverFolder.child("config");

            if (!configFolder.exists()) {
                continue;
            }

            var modFolder = configFolder.child("mods");

            if (!modFolder.exists()) {
                continue;
            }

            for (var mapFile : modFolder
                    .list(file -> file.getName().endsWith(".zip") || file.getName().endsWith(".jar"))) {
                result.computeIfAbsent(mapFile.name(), (_ignore) -> Tuples.of(mapFile, new ArrayList<>()))
                        .getT2()
                        .add(UUID.fromString(serverFolder.name()));
            }
        }

        return Flux.fromIterable(result.values()).flatMap(entry -> {
            try {
                var meta = loadMod(entry.getT1());

                return Mono.just(new ManagerModDto()//
                        .setFilename(entry.getT1().name())//
                        .setName(meta.name)
                        .setServers(entry.getT2())//
                        .setMeta(new ModMetaDto()//
                                .setAuthor(meta.author)//
                                .setDependencies(meta.dependencies.list())
                                .setDescription(meta.description)
                                .setDisplayName(meta.displayName)
                                .setHidden(meta.hidden)
                                .setInternalName(meta.internalName)
                                .setJava(meta.java)
                                .setMain(meta.main)
                                .setMinGameVersion(meta.minGameVersion)
                                .setName(meta.name)
                                .setRepo(meta.repo)
                                .setSubtitle(meta.subtitle)
                                .setVersion(meta.version)));
            } catch (Exception e) {
                e.printStackTrace();
                return Mono.empty();
            }
        });
    }

    public void deleteManagerMap(String filename) {
        var folder = Paths.get(Const.volumeFolderPath, "servers").toFile();

        if (!folder.exists()) {
            return;
        }

        for (var serverFolder : new Fi(folder).list()) {
            var configFolder = serverFolder.child("config");

            if (!configFolder.exists()) {
                continue;
            }

            var mapFolder = configFolder.child("maps");

            if (!mapFolder.exists()) {
                continue;
            }

            var mapFile = mapFolder.child(filename);

            if (mapFile.exists()) {
                mapFile.delete();
            }
        }
    }

    public void deleteManagerMod(String filename) {
        var folder = Paths.get(Const.volumeFolderPath, "servers").toFile();

        if (!folder.exists()) {
            return;
        }

        for (var serverFolder : new Fi(folder).list()) {
            var configFolder = serverFolder.child("config");

            if (!configFolder.exists()) {
                continue;
            }
            var modFolder = configFolder.child("mods");

            if (!modFolder.exists()) {
                continue;
            }

            var modFile = modFolder.child(filename);

            if (modFile.exists()) {
                modFile.delete();
            }
        }
    }

    public Flux<MapDto> getMaps(UUID serverId) {
        var folder = getFile(serverId, "maps");

        if (!folder.exists()) {
            return Flux.empty();
        }

        var maps = new Fi(folder).findAll()
                .map(file -> {
                    try {
                        return MapIO.createMap(file, true);
                    } catch (Throwable e) {
                        e.printStackTrace();
                        return new mindustry.maps.Map(file, 0, 0, new StringMap(), true, 0, Version.build);
                    }
                }).map(map -> new MapDto()//
                        .setName(map.name())//
                        .setFilename(map.file.name())
                        .setCustom(map.custom)
                        .setHeight(map.height)
                        .setWidth(map.width))
                .list();

        return Flux.fromIterable(maps);
    }

    public Flux<ModDto> getMods(UUID serverId) {
        var folder = getFile(serverId, "mods");

        if (!folder.exists()) {
            return Flux.empty();
        }

        var modFiles = new Fi(folder).findAll(file -> file.extension().equalsIgnoreCase("jar") || file.extension()
                .equalsIgnoreCase("zip"));

        var result = new ArrayList<ModDto>();
        for (var modFile : modFiles) {
            try {
                var meta = loadMod(modFile);
                result.add(new ModDto()//
                        .setFilename(modFile.name())//
                        .setName(meta.name)
                        .setMeta(new ModMetaDto()//
                                .setAuthor(meta.author)//
                                .setDependencies(meta.dependencies.list())
                                .setDescription(meta.description)
                                .setDisplayName(meta.displayName)
                                .setHidden(meta.hidden)
                                .setInternalName(meta.internalName)
                                .setJava(meta.java)
                                .setMain(meta.main)
                                .setMinGameVersion(meta.minGameVersion)
                                .setName(meta.name)
                                .setRepo(meta.repo)
                                .setSubtitle(meta.subtitle)
                                .setVersion(meta.version)));
            } catch (Exception error) {
                error.printStackTrace();

                result.add(new ModDto()//
                        .setFilename(modFile.name())//
                        .setName("Error")
                        .setMeta(new ModMetaDto()//
                                .setAuthor("Error")
                                .setName("Error")
                                .setDisplayName("Error")));
            }
        }

        return Flux.fromIterable(result);
    }

    private ModMeta findMeta(Fi file) {
        Fi metaFile = null;

        var metaFiles = List.of("mod.json", "mod.hjson", "plugin.json", "plugin.hjson");
        for (String name : metaFiles) {
            if ((metaFile = file.child(name)).exists()) {
                break;
            }
        }

        if (!metaFile.exists()) {
            return null;
        }

        ModMeta meta = json.fromJson(ModMeta.class, Jval.read(metaFile.readString()).toString(Jformat.plain));
        meta.cleanup();
        return meta;
    }

    private ModMeta loadMod(Fi sourceFile) throws Exception {
        ZipFi rootZip = null;

        try {
            Fi zip = sourceFile.isDirectory() ? sourceFile : (rootZip = new ZipFi(sourceFile));
            if (zip.list().length == 1 && zip.list()[0].isDirectory()) {
                zip = zip.list()[0];
            }

            ModMeta meta = findMeta(zip);

            if (meta == null) {
                log.warn("Mod @ doesn't have a '[mod/plugin].[h]json' file, delete and skipping.", zip);
                sourceFile.delete();
                throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid file: No mod.json found.");
            }

            return meta;
        } catch (Exception e) {
            if (e instanceof ApiError) {
                throw e;
            }
            // delete root zip file so it can be closed on windows
            if (rootZip != null)
                rootZip.delete();
            throw new RuntimeException("Can not load mod from: " + sourceFile.name(), e);
        }
    }

    public Flux<ServerFileDto> getFiles(UUID serverId, String path) {
        var folder = getFile(serverId, path);

        return Mono.just(folder) //
                .filter(file -> file.length() < MAX_FILE_SIZE)//
                .switchIfEmpty(ApiError.badRequest("file-too-big"))//
                .flatMapMany(file -> {
                    return file.isDirectory()//
                            ? Flux.fromArray(file.listFiles())//
                                    .map(child -> new ServerFileDto()//
                                            .name(child.getName())//
                                            .size(child.length())//
                                            .directory(child.isDirectory()))
                            : Flux.just(new ServerFileDto()//
                                    .name(file.getName())//
                                    .directory(file.isDirectory())//
                                    .size(file.length())//
                                    .data(readFile(file)));

                });
    }

    private String readFile(File file) {
        try {
            return Files.readString(file.toPath());
        } catch (Throwable e) {
            return "";
        }

    }

    public boolean fileExists(UUID serverId, String path) {
        var file = getFile(serverId, path);

        return file.exists();
    }

    public Mono<Void> createFile(UUID serverId, FilePart filePart, String path) {
        var folder = getFile(serverId, path);

        if (!folder.exists()) {
            folder.mkdirs();
        }

        if (folder.getPath().contains("mods")) {
            // Remove all old mods if exists
            var parts = filePart.filename().replace(".jar", "").split("_");
            if (parts.length == 2) {
                try {
                    var id = UUID.fromString(parts[0]);
                    new Fi(folder)//
                            .findAll()//
                            .select(f -> f.name().startsWith(id.toString()))//
                            .each(f -> {
                                f.delete();
                            });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        File file = new File(folder, filePart.filename());

        try {
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return filePart.transferTo(file);
    }

    public Mono<Void> deleteFile(UUID serverId, String path) {
        var file = getFile(serverId, path);

        if (!file.exists()) {
            log.info("Delete file: " + path + " is not exists");
            return Mono.empty();
        }

        if (file.isDirectory()) {
            var children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    child.delete();
                    log.info("Deleted: " + child.getPath());
                }
            }
        }
        log.info("Deleted: " + file.getPath());
        file.delete();

        return Mono.empty();
    }

    private Mono<Void> waitForHosting(GatewayClient.Server gateway) {
        return gateway.isHosting()//
                .flatMap(isHosting -> isHosting //
                        ? Mono.empty()
                        : ApiError.badRequest("Server is not hosting yet"))//
                .retryWhen(Retry.fixedDelay(50, Duration.ofMillis(100)))
                .then();
    }

    public Mono<Void> ok(UUID serverId) {
        return gatewayService.of(serverId).getServer().ok();
    }

    public Flux<NodeUsage> getUsage(UUID serverId) {
        return nodeManager.getNodeUsage(serverId);
    }

    public Mono<StatsDto> stats(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .getStats()//
                .onErrorResume(error -> {
                    Log.err(error.getMessage());
                    return Mono.empty();
                })
                .defaultIfEmpty(new StatsDto().setStatus(ServerStatus.NOT_RESPONSE));
    }

    public Mono<byte[]> getImage(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .getImage();
    }

    public Mono<Void> updatePlayer(UUID serverId, MindustryPlayerDto payload) {
        return gatewayService.of(serverId).getServer().updatePlayer(payload);
    }

    public Mono<JsonNode> setConfig(UUID serverId, String key, String value) {
        var file = nodeManager.getFile(serverId, "config/config.json");

        try {
            Utils.writeObject(file, key, value);
        } catch (IOException e) {
            throw new Error(e);
        }

        return Mono.just(Utils.readFile(file));
    }
}
