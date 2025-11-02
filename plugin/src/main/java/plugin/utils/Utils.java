package plugin.utils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Http.HttpRequest;
import dto.ModDto;
import dto.ModMetaDto;
import dto.PlayerDto;
import dto.ServerStateDto;
import dto.ServerStatus;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.game.Gamemode;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.io.SaveIO;
import mindustry.maps.Map;
import mindustry.maps.MapException;
import plugin.ServerController;
import plugin.handler.HttpServer;
import plugin.handler.SessionHandler;

public class Utils {

    private static boolean isHosting = false;

    public synchronized static void host(String mapName, String mode) {
        if (isHosting) {
            Log.warn("Can not start new host request while, previous still running");
        }

        isHosting = true;

        if (ServerController.isUnloaded) {
            Log.warn("Server unloaded, can not host");
            return;
        }
        if (Vars.state.isGame()) {
            Log.warn("Already hosting. Type 'stop' to stop hosting first.");
            return;
        }

        try {
            Gamemode preset = Gamemode.survival;

            if (mode != null) {
                try {
                    preset = Gamemode.valueOf(mode.toLowerCase());
                } catch (IllegalArgumentException event) {
                    Log.err("No gamemode '@' found.", mode);
                    return;
                }
            }

            Map result;
            if (mapName != null) {
                result = Vars.maps.all().find(map -> map.plainName().replace('_', ' ')
                        .equalsIgnoreCase(Strings.stripColors(mapName).replace('_', ' ')));

                if (result == null) {
                    Log.err("No map with name '@' found.", mapName);
                    return;
                }
            } else {
                result = Vars.maps.getShuffleMode().next(preset, Vars.state.map);
                Log.info("Randomized next map to be @.", result.plainName());
            }

            Vars.logic.reset();
            Vars.world.loadMap(result, result.applyRules(preset));
            Vars.state.rules = result.applyRules(preset);
            Vars.logic.play();
            Vars.netServer.openServer();

            HttpServer.sendStateUpdate();

        } catch (MapException event) {
            Log.err("@: @", event.map.plainName(), event.getMessage());
        } finally {
            isHosting = false;
        }
    }

    public static void appPostWithTimeout(Runnable r, String taskName) {
        appPostWithTimeout(r, 100, taskName);
    }

    private static void appPostWithTimeout(Runnable r, int timeout, String taskName) {
        Log.info("Start task: " + taskName);

        CompletableFuture<Void> v = new CompletableFuture<>();
        Core.app.post(() -> {
            try {
                r.run();
                v.complete(null);
            } catch (Throwable e) {
                v.completeExceptionally(e);
            }
        });
        try {
            v.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Time out when executing: " + r.toString() + " in " + timeout + "ms", e);
        }
    }

    public static <T> T appPostWithTimeout(Supplier<T> fn, String taskName) {
        return appPostWithTimeout(fn, 100, taskName);
    }

    private static <T> T appPostWithTimeout(Supplier<T> fn, int timeout, String taskName) {
        Log.debug("Start task: " + taskName);

        CompletableFuture<T> future = new CompletableFuture<T>();
        Core.app.post(() -> {
            try {
                future.complete(fn.get());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Time out when executing: " + fn.toString() + " in " + timeout + "ms", e);
        }
    }

    public static String readInputStreamAsString(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, "UTF-8"))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Throwable error) {
            throw new RuntimeException(error);
        }
    }

    public static ServerStateDto getState() {
        mindustry.maps.Map map = Vars.state.map;
        String mapName = map != null ? map.name() : "";

        List<ModDto> mods = Vars.mods == null //
                ? Arrays.asList()
                : Vars.mods.list()
                        .select((mod) -> mod.meta.hidden == false)
                        .map(mod -> new ModDto()//
                                .setFilename(mod.file.absolutePath())//
                                .setName(mod.name)
                                .setMeta(ModMetaDto.from(mod.meta)))
                        .list();

        ArrayList<Player> players = new ArrayList<Player>();
        Groups.player.forEach(players::add);

        List<PlayerDto> p = players.stream()//
                .map(player -> PlayerDto.from(player)//
                        .setJoinedAt(SessionHandler.contains(player) //
                                ? SessionHandler.get(player).joinedAt
                                : Instant.now().toEpochMilli()))
                .collect(Collectors.toList());

        int kicks = Vars.netServer.admins.kickedIPs
                .values()
                .toSeq()
                .select(value -> Time.millis() - value < 0).size;

        return new ServerStateDto()//
                .setPlayers(p)//
                .setMods(mods)//
                .setKicks(kicks)//
                .setMapName(mapName)
                .setVersion(Version.combined())
                .setStartedAt(Core.settings.getLong("startedAt", System.currentTimeMillis()))
                .setServerId(ServerController.SERVER_ID)
                .setStatus(Vars.state.isGame() //
                        ? Vars.state.isPaused()//
                                ? ServerStatus.PAUSED
                                : ServerStatus.ONLINE
                        : ServerStatus.STOP);
    }

    private static final String MAP_PREVIEW_FILE_NAME = "map-preview.msav";

    public static byte[] mapPreview() {
        Fi tempFile = new Fi(MAP_PREVIEW_FILE_NAME);

        try {
            tempFile.delete();
            SaveIO.save(tempFile);
            String boundary = UUID.randomUUID().toString(); // unique boundary
            byte[] multipartBody = buildMultipartBody(boundary, "file", MAP_PREVIEW_FILE_NAME, tempFile.readBytes());

            HttpRequest request = HttpUtils
                    .post("https://api.mindustry-tool.com", "api", "v4", "maps", "image")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .content(new ByteArrayInputStream(multipartBody), multipartBody.length);

            return HttpUtils.send(request, byte[].class);

        } catch (Throwable throwable) {
            Log.err(throwable);

            return new byte[0];
        }
    }

    private static byte[] buildMultipartBody(String boundary, String fieldName, String fileName, byte[] fileBytes)
            throws IOException {

        String LINE_FEED = "\r\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);

        // --- Start multipart ---
        writer.append("--").append(boundary).append(LINE_FEED);
        writer.append("Content-Disposition: form-data; name=\"")
                .append(fieldName)
                .append("\"; filename=\"")
                .append(fileName)
                .append("\"")
                .append(LINE_FEED);
        writer.append("Content-Type: application/octet-stream").append(LINE_FEED);
        writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);
        writer.append(LINE_FEED);
        writer.flush();

        // --- File content ---
        out.write(fileBytes);
        out.flush();

        writer.append(LINE_FEED);
        writer.append("--").append(boundary).append("--").append(LINE_FEED);
        writer.flush();

        return out.toByteArray();
    }
}
