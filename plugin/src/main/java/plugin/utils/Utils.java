package plugin.utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import arc.Core;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Gamemode;
import mindustry.maps.Map;
import mindustry.maps.MapException;
import plugin.ServerController;

public class Utils {

    private static boolean isHosting = false;

    public synchronized static void host(String mapName, String mode) {
        Core.app.post(() -> {
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

                Log.info("Logic reset");
                Vars.logic.reset();
                Log.info("Map reset");
                Vars.world.loadMap(result, result.applyRules(preset));
                Log.info("Apply rules");
                Vars.state.rules = result.applyRules(preset);
                Log.info("Play logic");
                Vars.logic.play();
                Log.info("Open server");
                Vars.netServer.openServer();
                Log.info("Server started.");
            } catch (MapException event) {
                Log.err("@: @", event.map.plainName(), event.getMessage());
            } finally {
                isHosting = false;
            }
        });
    }

    public static void appPostWithTimeout(Runnable r) {
        appPostWithTimeout(r, 5000);
    }

    public static void appPostWithTimeout(Runnable r, int timeout) {
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

    public static <T> T appPostWithTimeout(Supplier<T> fn) {
        return appPostWithTimeout(fn, 5000);
    }

    public static <T> T appPostWithTimeout(Supplier<T> fn, int timeout) {
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
}
