package plugin;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.pf4j.Plugin;

import arc.util.*;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.gen.Groups;
import plugin.handler.ApiGateway;
import plugin.handler.ClientCommandHandler;
import plugin.handler.EventHandler;
import plugin.handler.HttpServer;
import plugin.handler.HudHandler;
import plugin.handler.VoteHandler;
import plugin.handler.ServerCommandHandler;
import plugin.handler.SessionHandler;
import plugin.workflow.Workflow;
import loader.MindustryToolPlugin;

public class ServerController extends Plugin implements MindustryToolPlugin {

    public static final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));
    public static boolean isUnloaded = false;

    private static final ExecutorService BACKGROUND_TASK_EXECUTOR = Executors.newWorkStealingPool();

    public static void backgroundTask(Runnable r) {
        BACKGROUND_TASK_EXECUTOR.submit(() -> {
            try {
                r.run();
            } catch (Exception e) {
                Log.err(e);
            }
        });
    }

    public static final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors
            .newSingleThreadScheduledExecutor();

    public ServerController() {
        Log.info("Server controller created: " + this);
    }

    @Override
    protected void finalize() throws Throwable {
        System.out.println("Finalizing " + this);
    }

    @Override
    public void start() {
        Log.info("Server controller started: " + this);
    }

    @Override
    public void init() {
        HttpServer.init();
        Workflow.init();
        EventHandler.init();
        ApiGateway.init();
        HudHandler.init();

        BACKGROUND_SCHEDULER.schedule(() -> {
            try {
                if (!Vars.state.isGame()) {
                    Log.info("Server not hosting, auto host");
                    ApiGateway.host(SERVER_ID.toString());
                }
            } catch (Exception e) {
                Log.err(e);
            }
        }, 30, TimeUnit.SECONDS);

        BACKGROUND_SCHEDULER.schedule(() -> {
            if (!Vars.state.isPaused() && Groups.player.size() == 0) {
                Vars.state.set(State.paused);
                Log.info("No player: paused");
            }

            if (HttpServer.isConnected()) {
                ApiGateway.requestConnection();
            }
        }, 10, TimeUnit.SECONDS);

        Log.info("Server controller initialized.");
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        ServerCommandHandler.registerCommands(handler);
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        ClientCommandHandler.registerCommands(handler);
    }

    @Override
    public void onEvent(Object event) {
        try {
            if (isUnloaded) {
                return;
            }

            PluginEvents.fire(event);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void unload() {
        isUnloaded = true;

        ClientCommandHandler.unload();
        ServerCommandHandler.unload();
        SessionHandler.clear();
        Workflow.clear();
        EventHandler.unload();
        HudHandler.unload();
        VoteHandler.unload();
        ApiGateway.unload();
        HttpServer.unload();
        PluginEvents.clear();

        BACKGROUND_TASK_EXECUTOR.shutdownNow();
        Log.info("Background task executor shutdown");
        BACKGROUND_SCHEDULER.shutdownNow();
        Log.info("Background scheduler shutdown");

        Log.info("Server controller stopped: " + this);
    }

    @Override
    public void stop() {
        Log.info("Stop: " + this);
    }

    @Override
    public void delete() {
        Log.info("Server controller deleted: " + this);
    }
}
