package plugin.utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Pixmap;
import arc.util.Log;
import arc.util.Reflect;
import arc.util.Strings;
import arc.util.Time;
import dto.ModDto;
import dto.ModMetaDto;
import dto.PlayerDto;
import dto.ServerStateDto;
import dto.ServerStatus;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.game.Gamemode;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.maps.Map;
import mindustry.maps.MapException;
import mindustry.type.UnitType;
import plugin.ServerControl;
import plugin.handler.SessionHandler;

public class Utils {

    private static boolean isHosting = false;

    public synchronized static void host(String mapName, String mode) {
        if (isHosting) {
            Log.warn("Can not start new host request while, previous still running");
        }

        isHosting = true;

        if (ServerControl.isUnloaded) {
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
                    Core.settings.put("lastServerMode", preset.name());

                    Class<?> clazz = Class.forName("mindustry.server.ServerControl");

                    for (var listener : Core.app.getListeners()) {
                        if (listener.getClass().equals(clazz)) {
                            Reflect.set(clazz, listener, "lastMode", preset);
                            Log.info("Set gamemode to: " + preset.name());
                            break;
                        }
                    }
                } catch (Exception event) {
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

            Log.info("Hosting map @ with mode @.", result.plainName(), preset.name());

            Vars.logic.reset();
            Vars.world.loadMap(result, result.applyRules(preset));
            Vars.state.rules = result.applyRules(preset);
            Vars.logic.play();
            Vars.netServer.openServer();

        } catch (MapException event) {
            Log.err("@: @", event.map.plainName(), event.getMessage());
        } finally {
            isHosting = false;
        }
    }

    public static void appPostWithTimeout(Runnable r, String taskName) {
        appPostWithTimeout(r, 500, taskName);
    }

    private static synchronized void appPostWithTimeout(Runnable r, int timeout, String taskName) {
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
        return appPostWithTimeout(fn, 500, taskName);
    }

    private static synchronized <T> T appPostWithTimeout(Supplier<T> fn, int timeout, String taskName) {
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
                        .map(mod -> new ModDto()//
                                .setFilename(mod.file.absolutePath())//
                                .setName(mod.meta.name)
                                .setMeta(ModMetaDto.from(mod.meta)))
                        .list();

        ArrayList<Player> players = new ArrayList<Player>();
        Groups.player.forEach(players::add);

        List<PlayerDto> p = SessionHandler.get()
                .values()
                .stream()
                .map(session -> PlayerDto.from(session.player).setJoinedAt(session.joinedAt))
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
                .setServerId(ServerControl.SERVER_ID)
                .setStatus(Vars.state.isGame() //
                        ? Vars.state.isPaused()//
                                ? ServerStatus.PAUSED
                                : ServerStatus.ONLINE
                        : ServerStatus.STOP);
    }

    public static byte[] mapPreview2() {
        Pixmap pix = null;
        try {
            if (Vars.state.map != null) {
                pix = MapIO.generatePreview(Vars.world.tiles);
                Fi file = Vars.dataDirectory.child(MAP_PREVIEW_IMAGE_FILE_NAME);
                file.writePng(pix);
                pix.dispose();

                return file.readBytes();
            }

            return new byte[] {};
        } catch (Exception e) {
            e.printStackTrace();
            return new byte[] {};
        } finally {
            if (pix != null) {
                pix.dispose();
            }
        }
    }

    private static final String MAP_PREVIEW_FILE_NAME = "map-preview.msav";
    private static final String MAP_PREVIEW_IMAGE_FILE_NAME = "map-preview-image.png";

    public static byte[] mapPreview() {
        Fi tempFile = Vars.dataDirectory.child(MAP_PREVIEW_FILE_NAME);
        Fi tempImageFile = Vars.dataDirectory.child(MAP_PREVIEW_IMAGE_FILE_NAME);

        if (tempFile.isDirectory()) {
            tempFile.deleteDirectory();
        }

        if (tempImageFile.isDirectory()) {
            tempImageFile.deleteDirectory();
        }

        try {
            tempFile.file().createNewFile();
            tempImageFile.file().createNewFile();

            Core.app.post(() -> {
                try {
                    SaveIO.save(tempFile);
                } catch (Exception e) {
                    Log.err("Failed to generate save file", e);
                }
            });

            HashMap<String, String> body = new HashMap<>();
            byte[] bytes = tempFile.readBytes();

            if (bytes.length == 0) {
                return new byte[0];
            }

            byte[] imageBytes = tempImageFile.readBytes();

            body.put("data", Base64.getEncoder().encodeToString(bytes));

            HttpUtils
                    .post("https://api.mindustry-tool.com", "api", "v4", "maps", "image-json")
                    .header("Content-Type", "application/json")
                    .content(JsonUtils.toJsonString(body))
                    .timeout(30000)
                    .error(Log::err)
                    .submit((res) -> {
                        tempImageFile.write(res.getResultAsStream(), false);
                    });

            if (imageBytes.length == 0) {
                return new byte[0];
            }

            return imageBytes;

        } catch (Throwable throwable) {
            Log.err(throwable.getMessage());
        }

        return new byte[0];
    }

    public static Locale parseLocale(String locale) {
        if (locale == null) {
            return Locale.ENGLISH;
        }

        String[] parts = locale.replace("_", "-").split("-");

        return parts.length > 0 ? Locale.forLanguageTag(parts[0]) : Locale.ENGLISH;
    }

    public static void forEachPlayerLocale(BiConsumer<Locale, List<Player>> cons) {
        HashMap<Locale, List<Player>> groupByLocale = new HashMap<>();

        Groups.player.forEach(
                p -> groupByLocale.computeIfAbsent(parseLocale(p.locale()), k -> new ArrayList<>()).add(p));

        groupByLocale.forEach(cons);
    }

    public static char icon(UnitType type) {
        return Reflect.get(Iconc.class, "unit" + Strings.capitalize(type.name));
    }

    public static String padRight(String text, int length) {
        if (text.length() >= length)
            return text;
        StringBuilder sb = new StringBuilder(text);

        for (int i = text.length(); i < length; i++) {
            sb.append(" ");
        }

        return sb.toString();
    }
}
