package plugin.host;

import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.Reflect;
import arc.util.Strings;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.Gamemode;
import mindustry.io.SaveIO;
import mindustry.maps.Map;
import mindustry.maps.MapException;
import plugin.Control;
import plugin.PluginState;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.MainThread;
import plugin.annotations.Schedule;

@Component
@RequiredArgsConstructor
public class HostService {
    public final Fi SAVE_FILE = Vars.dataDirectory.child("LAST_MAP.msav");

    @Destroy
    private void saveOnExit() {
        try {
            if (Vars.state.isPlaying()) {
                SaveIO.save(SAVE_FILE);
                Log.info("Save map to: " + SAVE_FILE);
            }
        } catch (Exception e) {
            Log.err("Failed to save map", e);
        }
    }

    @MainThread
    @Schedule(delay = 5, fixedDelay = 5, unit = TimeUnit.MINUTES)
    private void autoSave() {
        if (Vars.state.isPlaying()) {
            SaveIO.save(SAVE_FILE);
        }
    }

    public synchronized void host(String mapName, String mode) {
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

                    Class<?> clazz = Class.forName("mindustry.server.ServerControl");

                    for (var listener : Core.app.getListeners()) {
                        if (listener.getClass().equals(clazz)) {
                            Reflect.set(clazz, listener, "lastMode", preset);
                            Log.info("Last gamemode: " + preset.name());
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
                if (result == null) {
                    result = Vars.maps.getShuffleMode().next(preset, Vars.state.map);
                    Log.info("Randomized next map to be @.", result.plainName());
                }
            } else {
                result = Vars.maps.all().find(map -> map.plainName().replace('_', ' ')
                        .equalsIgnoreCase(Strings.stripColors(mapName).replace('_', ' ')));

                if (result == null) {
                    Log.err("No map with name '@' found.", mapName);
                    return;
                }
            }

            Log.info("Hosting map @ with mode @.", result.plainName(), preset.name());

            Vars.logic.reset();

            try {
                Vars.world.loadMap(result, result.applyRules(preset));
            } catch (Exception e) {
                Log.info("Fail to host map: " + result.name(), e);
                result = Vars.maps.getShuffleMode().next(preset, Vars.state.map);
                Log.info("Randomized next map to be @.", result.plainName());
                Vars.world.loadMap(result, result.applyRules(preset));
            }

            Vars.state.rules = result.applyRules(preset);
            Vars.logic.play();
            Vars.netServer.openServer();

        } catch (MapException event) {
            Log.err("@: @", event.map.plainName(), event.getMessage());
        }
    }
}
