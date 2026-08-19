package plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import arc.Core;
import arc.util.*;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.gen.Groups;
import plugin.annotations.Schedule;
import plugin.commands.ClientCommandHandler;
import plugin.commands.ServerCommandHandler;
import plugin.core.Registry;
import plugin.event.PluginUnloadEvent;
import plugin.event.UnloadServerEvent;
import plugin.utils.TimeUtils;
import plugin.event.KickEvent;

public class Control extends mindustry.mod.Plugin {

    public static final Instant start = Instant.now();
    public static final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));

    public static PluginState state = PluginState.LOADING;

    private static String[] tags = { "", "", "[yellow]", "[red]", "" };
    private static Pattern kickPattern = Pattern.compile(
            "Kicking connection\\s+(\\S+)\\s*/\\s*(\\S+)\\s*;\\s*Reason:\\s*(.*)"
    //
    );

    @Override
    public void init() {
        Instant start = Instant.now();

        Log.useColors = false;
        Log.logger = (level1, text) -> {
            Matcher matcher = kickPattern.matcher(text);

            if (matcher.find()) {
                String address = matcher.group(1);
                String uuid = matcher.group(2);
                String reason = matcher.group(3);

                PluginEvents.fire(new KickEvent(address, uuid, reason));
            }

            String result = Log.format(tags[level1.ordinal()] + text + "&fr");
            System.out.println(result);
        };

        Core.settings.put("startedAt", System.currentTimeMillis());

        try {
            PluginEvents.register();    
            PluginEvents.on(UnloadServerEvent.class, this::unload);

            Registry.init(getClass().getPackage().getName());
            Registry.get(this.getClass());

            state = PluginState.LOADED;

            Log.info("Plugin loaded in " + TimeUtils.toString(Duration.between(start, Instant.now())));

        } catch (Exception e) {
            Log.err("Failed to init plugin", e);
            unload(new UnloadServerEvent(true));
        }
    }

    @Schedule(delay = 10, fixedDelay = 3, unit = TimeUnit.MINUTES)
    private void checkInvalidState() {
        if (Vars.state.isGame() && !Vars.net.server() && state == PluginState.LOADED) {
            Log.err("[scarlet]Server in invalid state, auto exit: state=@, server=@, plugin-state=@",
                    Vars.state.getState().name(),
                    Vars.net.server(), state);
            PluginEvents.fire(new UnloadServerEvent(true));
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        Log.debug("[gray]Register server commands");
        Registry.get(ServerCommandHandler.class).registerCommands(handler);
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        Log.debug("[gray]Register client commands");
        Registry.get(ClientCommandHandler.class).registerCommands(handler);
    }

    public synchronized void unload(UnloadServerEvent event) {
        try {
            if (state == PluginState.UNLOADED) {
                return;
            }

            state = PluginState.UNLOADED;

            Log.info("Unload");

            Tasks.destroy();

            PluginEvents.fire(new PluginUnloadEvent());
            Registry.destroy();
            PluginEvents.unregister();

            try {
                Core.settings.forceSave();
            } catch (Exception e) {
                Log.err("Failed to save settings", e);
            }

            Log.info("Server controller unloaded after running for "
                    + TimeUtils.toString(Duration.between(start, Instant.now())));
        } catch (Exception e) {
            Log.err("Failed to unload plugin", e);
        } finally {
            if (event.exit) {
                System.exit(0);
            }
        }
    }

    @Schedule(delay = 2, fixedDelay = 2, unit = TimeUnit.SECONDS)
    private void autoPause() {
        if (Vars.state.isPlaying() && Groups.player.size() == 0) {
            Vars.state.set(State.paused);
            Log.info("No player: paused");
        } else if (Vars.state.isPaused() && Groups.player.size() > 0) {
            Vars.state.set(State.playing);
            Log.info("Player joined: playing");
        }
    }
}
