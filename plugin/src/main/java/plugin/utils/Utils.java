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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Pixmap;
import arc.util.Log;
import arc.util.Reflect;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Http.HttpStatusException;
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
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.maps.Map;
import mindustry.maps.MapException;
import plugin.Control;
import plugin.PluginState;
import plugin.core.Registry;
import plugin.core.Scheduler;

public class Utils {

    private static final ConcurrentHashMap<String, Object> hostingLock = new ConcurrentHashMap<>();

    public static final Object getHostingLock(String serverId) {
        return hostingLock.computeIfAbsent(serverId, k -> new Object());
    }

    public static void releaseHostingLock(String serverId) {
        hostingLock.remove(serverId);
    }

    public synchronized static void host(String mapName, String mode) {
        var lock = getHostingLock(Control.SERVER_ID.toString());

        synchronized (lock) {
            if (Control.state == PluginState.UNLOADED) {
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
                                Log.info("[sky]Set gamemode to: " + preset.name());
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
                    Log.info("[sky]Randomized next map to be @.", result.plainName());
                }

                Log.info("[sky]Hosting map @ with mode @.", result.plainName(), preset.name());

                Vars.logic.reset();
                Vars.world.loadMap(result, result.applyRules(preset));
                Vars.state.rules = result.applyRules(preset);
                Vars.logic.play();
                Vars.netServer.openServer();

            } catch (MapException event) {
                Log.err("@: @", event.map.plainName(), event.getMessage());
            } finally {
                releaseHostingLock(Control.SERVER_ID.toString());
            }
        }
    }

    public static void appPostWithTimeout(Runnable r, String taskName) {
        appPostWithTimeout(r, 500, taskName);
    }

    private static synchronized void appPostWithTimeout(Runnable r, int timeout, String taskName) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        Core.app.post(() -> {
            ScheduledFuture<?> timeoutTask = Registry.get(Scheduler.class)
                    .schedule(() -> future.completeExceptionally(new TimeoutException("Task timeout: " + taskName)),
                            timeout, TimeUnit.MILLISECONDS);

            try {
                r.run();
                timeoutTask.cancel(true);
                future.complete(null);
            } catch (Throwable e) {
                timeoutTask.cancel(true);
                future.completeExceptionally(e);
            }
        });
        try {
            future.get(Math.max(10 * 1000, timeout), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Time out when executing: " + r.toString() + " in " + timeout + "ms", e);
        }
    }

    public static <T> T appPostWithTimeout(Supplier<T> fn, String taskName) {
        return appPostWithTimeout(fn, 200, taskName);
    }

    private static synchronized <T> T appPostWithTimeout(Supplier<T> fn, int timeout, String taskName) {
        Log.debug("Start task: " + taskName);

        CompletableFuture<T> future = new CompletableFuture<T>();
        Core.app.post(() -> {
            ScheduledFuture<?> timeoutTask = Registry.get(Scheduler.class).schedule(
                    () -> future.completeExceptionally(new TimeoutException("Task timeout: " + taskName)), timeout,
                    TimeUnit.MILLISECONDS);
            try {
                var result = fn.get();
                timeoutTask.cancel(true);
                future.complete(result);
            } catch (Throwable e) {
                timeoutTask.cancel(true);
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(Math.max(10 * 1000, timeout), TimeUnit.MILLISECONDS);
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

        List<PlayerDto> p = Registry.get(plugin.service.SessionHandler.class).get()
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
                .setServerId(Control.SERVER_ID)
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

        if (!Vars.state.isGame()) {
            return new byte[0];
        }

        var bytes = appPostWithTimeout(() -> {
            SaveIO.save(tempFile);
            return (tempFile.readBytes());
        }, 200, "Generate map preview");

        if (bytes.length == 0) {
            return new byte[0];
        }

        HashMap<String, String> body = new HashMap<>();

        body.put("data", Base64.getEncoder().encodeToString(bytes));

        HttpUtils
                .post("https://api.mindustry-tool.com", "api", "v4", "maps", "image-json")
                .header("Content-Type", "application/json")
                .content(JsonUtils.toJsonString(body))
                .timeout(120000)
                .error(error -> {
                    if (error instanceof HttpStatusException httpStatusException) {
                        Log.err("Fail to generate map preview", httpStatusException.response.getResultAsString());
                    } else {
                        Log.err("Fail to generate map preview", error.getMessage());
                    }
                })
                .submit((res) -> {
                    tempImageFile.write(res.getResultAsStream(), false);
                });

        byte[] imageBytes = tempImageFile.readBytes();

        if (imageBytes.length == 0) {
            return new byte[0];
        }

        return imageBytes;
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

    public static String padRight(String text, int length) {
        if (text == null) {
            text = "";
        }

        int spaceWidth = TextWidth.measure(" ");
        int targetWidth = spaceWidth * length;

        String plain = Strings.stripColors(text);

        int currentWidth = TextWidth.measure(plain);

        if (currentWidth >= targetWidth) {
            return text;
        }

        StringBuilder sb = new StringBuilder(text);

        while (currentWidth < targetWidth) {
            sb.append(' ');
            currentWidth += spaceWidth;
        }

        return sb.toString();
    }

    public static <T> T find(Iterable<T> iterable, Predicate<T> predicate) {
        for (T t : iterable) {
            if (predicate.test(t)) {
                return t;
            }
        }
        return null;
    }

    public static <T, R> R find(Iterable<T> iterable, Predicate<T> predicate, Function<T, R> mapper) {
        for (T t : iterable) {
            if (predicate.test(t)) {
                return mapper.apply(t);
            }
        }
        return null;
    }
}
