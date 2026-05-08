package plugin.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import arc.ApplicationListener;
import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.Reflect;
import arc.util.Strings;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.Gamemode;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.maps.Map;
import mindustry.maps.MapException;
import plugin.Control;
import plugin.PluginState;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.annotations.MainThread;
import plugin.annotations.Schedule;
import plugin.core.Registry;

@Component
@RequiredArgsConstructor
public class HostService {
    private final ConcurrentHashMap<String, Object> hostingLock = new ConcurrentHashMap<>();
    public final Fi SAVE_FILE = Vars.dataDirectory.child("LAST_MAP.msav");

    public boolean isHosting(String serverId) {
        return hostingLock.containsKey(serverId);
    }

    public String hostLock(String serverId, Supplier<String> fn) {
        Object lock = hostingLock.computeIfAbsent(serverId, k -> new Object());

        synchronized (lock) {
            try {
                Log.info("[sky]Hosting server: " + serverId);
                return fn.get();
            } catch (Exception e) {
                throw new RuntimeException("Host failed", e);
            } finally {
                hostingLock.remove(serverId);
                Log.info("[sky]Finish hosting server: " + serverId);
            }
        }
    }

    @Init
    private void saveOnExit() {
        Core.app.addListener(new ApplicationListener() {
            @Override
            public void exit() {
                if (Vars.state.isPlaying()) {
                    SaveIO.save(SAVE_FILE);
                    Log.info("[sky]Save map to: " + SAVE_FILE);
                }
            }
        });
    }

    @MainThread
    @Schedule(delay = 5, fixedDelay = 5, unit = TimeUnit.MINUTES)
    private void autoSave() {
        if (Vars.state.isPlaying()) {
            SaveIO.save(SAVE_FILE);
        }
    }

    @Schedule(delay = 20, fixedDelay = 30, unit = TimeUnit.SECONDS)
    private void autoHost() {
        try {
            if (!Vars.state.isGame() && !isHosting(Control.SERVER_ID.toString())) {
                Log.info("[sky]Server not hosting, auto host");
                Registry.get(ApiGateway.class).hostRemoteServer(Control.SERVER_ID.toString());
            }
        } catch (Exception e) {
            Log.err("Failed to host server", e);
        }
    }

    public synchronized void host(String mapName, String mode) {
        boolean shouldLoadSave = true;

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
                    String lastServerMode = Core.settings.getString("lastServerMode", "");

                    if (!lastServerMode.equals(preset.name())) {
                        shouldLoadSave = false;
                    }

                    Core.settings.put("lastServerMode", preset.name());

                    Class<?> clazz = Class.forName("mindustry.server.ServerControl");

                    for (var listener : Core.app.getListeners()) {
                        if (listener.getClass().equals(clazz)) {
                            Reflect.set(clazz, listener, "lastMode", preset);
                            Log.info("[sky]Last gamemode: " + preset.name());
                            break;
                        }
                    }
                } catch (Exception error) {
                    Log.err("Fail to set gamemode to " + preset.name(), error);
                    return;
                }
            }

            Map result = null;

            if (mapName == null) {
                if (shouldLoadSave && SAVE_FILE.exists()) {
                    try {
                        result = MapIO.createMap(SAVE_FILE, true);
                        Log.info("[sky]Loaded saved map @.", result.name());
                    } catch (Exception e) {
                        Log.err(e);
                    }
                }

                if (result == null) {
                    result = Vars.maps.getShuffleMode().next(preset, Vars.state.map);
                    Log.info("[sky]Randomized next map to be @.", result.plainName());
                }
            } else {
                result = Vars.maps.all().find(map -> map.plainName().replace('_', ' ')
                        .equalsIgnoreCase(Strings.stripColors(mapName).replace('_', ' ')));

                if (result == null) {
                    Log.err("No map with name '@' found.", mapName);
                    return;
                }
            }

            Log.info("[sky]Hosting map @ with mode @.", result.plainName(), preset.name());

            Vars.logic.reset();
            Vars.world.loadMap(result, result.applyRules(preset));
            Vars.state.rules = result.applyRules(preset);
            Vars.logic.play();
            Vars.netServer.openServer();

        } catch (MapException event) {
            Log.err("@: @", event.map.plainName(), event.getMessage());
        }
    }
}
