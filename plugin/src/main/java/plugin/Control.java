package plugin;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.Events;
import arc.util.*;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import plugin.service.ApiGateway;
import plugin.utils.Utils;
import plugin.annotations.Schedule;
import plugin.commands.ClientCommandHandler;
import plugin.commands.ServerCommandHandler;
import plugin.core.Registry;
import plugin.database.DB;
import plugin.event.PluginUnloadEvent;
import plugin.event.UnloadServerEvent;

public class Control extends mindustry.mod.Plugin {

    public static PluginState state = PluginState.LOADING;
    public static final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));

    private static String[] tags = { "", "", "[yellow]", "[red]", "" };

    @Override
    public void init() {
        Log.useColors = false;
        Log.logger = (level1, text) -> {
            String result = Log.format(tags[level1.ordinal()] + text + "&fr");
            System.out.println(result);
        };

        Core.settings.put("startedAt", System.currentTimeMillis());

        try {
            DB.init();
            Registry.init(getClass().getPackage().getName());
            Registry.get(this.getClass());

            registerEventListener();
            registerTriggerListener();

            state = PluginState.LOADED;

            PluginEvents.run(UnloadServerEvent.class, this::unload);

            Log.info("Plugin loaded");

        } catch (Exception e) {
            Log.err("Failed to init plugin", e);
            unload();
        }
    }

    @Schedule(delay = 10, fixedDelay = 3, unit = TimeUnit.MINUTES)
    private void checkInvalidState() {
        if (Vars.state.isGame() && !Vars.net.server() && state == PluginState.LOADED) {
            Log.err("[scarlet]Server in invalid state, auto exit: state=@, server=@, plugin-state=@",
                    Vars.state.getState().name(),
                    Vars.net.server(), state);
            unload();
            System.exit(0);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        Registry.get(ServerCommandHandler.class).registerCommands(handler);
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        Registry.get(ClientCommandHandler.class).registerCommands(handler);
    }

    public void unload() {
        state = PluginState.UNLOADED;

        Log.info("Unload");

        Tasks.destroy();

        PluginEvents.fire(new PluginUnloadEvent());
        Registry.destroy();
        DB.close();
        PluginEvents.unregister();

        Log.info("Server controller unloaded");
    }

    @Schedule(delay = 20, fixedDelay = 30, unit = TimeUnit.SECONDS)
    private void autoHost() {
        try {
            if (!Vars.state.isGame() && !Utils.isHosting(SERVER_ID.toString())) {
                Log.info("[sky]Server not hosting, auto host");
                Registry.get(ApiGateway.class).host(SERVER_ID.toString());
            }
        } catch (Exception e) {
            Log.err("Failed to host server", e);
        }
    }

    @Schedule(delay = 30, fixedDelay = 2, unit = TimeUnit.SECONDS)
    private void autoPause() {
        if (Vars.state.isPlaying() && Groups.player.size() == 0) {
            Vars.state.set(State.paused);
            Log.info("No player: paused");
        } else if (Vars.state.isPaused() && Groups.player.size() > 0) {
            Vars.state.set(State.playing);
            Log.info("Player joined: playing");
        }
    }

    @Schedule(delay = 0, fixedDelay = 5, unit = TimeUnit.SECONDS)
    private void detectOverflow()
            throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        var runnables = Core.app.getClass().getDeclaredField("runnables");
        runnables.setAccessible(true);
        TaskQueue taskQueue = (TaskQueue) runnables.get(Core.app);
        if (taskQueue.size() > 5000) {
            Log.err("[scarlet]Runnables overflow: @", taskQueue.size());
        }
    }

    private void registerEventListener() {
        for (Class<?> clazz : EventType.class.getDeclaredClasses()) {
            Events.on(clazz, event -> {
                try {
                    if (state != PluginState.LOADED) {
                        return;
                    }

                    PluginEvents.fire(event);

                } catch (Exception e) {
                    Log.err("Failed to invoke event @", event, e);
                    Log.err(e);
                }
            });
        }
    }

    private void registerTriggerListener() {
        for (EventType.Trigger trigger : EventType.Trigger.values()) {
            Events.run(trigger, () -> {
                try {
                    if (state != PluginState.LOADED) {
                        return;
                    }

                    PluginEvents.fire(trigger);

                } catch (Exception e) {
                    Log.err("Failed to invoke trigger @", trigger);
                    Log.err(e);
                }
            });
        }
    }
}
