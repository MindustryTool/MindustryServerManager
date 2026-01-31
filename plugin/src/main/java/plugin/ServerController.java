package plugin;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.pf4j.Plugin;

import arc.func.Func;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import plugin.handler.ApiGateway;
import plugin.handler.ClientCommandHandler;
import plugin.handler.EventHandler;
import plugin.handler.HttpServer;
import plugin.handler.VoteHandler;
import plugin.menus.PluginMenu;
import plugin.utils.Utils;
import plugin.handler.ServerCommandHandler;
import plugin.handler.SessionHandler;
import plugin.handler.SnapshotHandler;
import plugin.workflow.Workflow;
import loader.MindustryToolPlugin;

public class ServerController extends Plugin implements MindustryToolPlugin {

    public static boolean isUnloaded = false;

    public static final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));
    public static final ExecutorService BACKGROUND_TASK_EXECUTOR = Executors.newWorkStealingPool();
    public static final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    public ServerController() {
        Log.info("Server controller created: " + this);
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
        PluginMenu.init();
        SessionHandler.init();
        SnapshotHandler.init();

        BACKGROUND_SCHEDULER.schedule(ServerController::autoHost, 30, TimeUnit.SECONDS);
        BACKGROUND_SCHEDULER.schedule(ServerController::autoPause, 10, TimeUnit.SECONDS);
        BACKGROUND_SCHEDULER.schedule(ServerController::sendTips, 3, TimeUnit.MINUTES);

        Call.sendMessage("[scarlet]Server controller restarted");
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
        VoteHandler.unload();
        ApiGateway.unload();
        HttpServer.unload();
        PluginEvents.clear();
        PluginMenu.unload();
        SnapshotHandler.unload();

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

    @Override
    protected void finalize() throws Throwable {
        System.out.println("Finalizing " + this);
    }

    public static void backgroundTask(String name, Runnable r) {
        BACKGROUND_TASK_EXECUTOR.submit(() -> {
            try {
                r.run();
            } catch (Exception e) {
                Log.err("Failed to execute background task: " + name, e);
            }
        });
    }

    private static void autoHost() {
        try {
            if (!Vars.state.isGame()) {
                Log.info("Server not hosting, auto host");
                ApiGateway.host(SERVER_ID.toString());
            }
        } catch (Exception e) {
            Log.err("Failed to host server", e);
        }
    }

    private static void autoPause() {
        if (!Vars.state.isPaused() && Groups.player.size() == 0) {
            Vars.state.set(State.paused);
            Log.info("No player: paused");
        }

        if (HttpServer.isConnected()) {
            ApiGateway.requestConnection();
        }
    }

    private static void sendTips() {
        Seq<Func<Locale, String>> tips = new Seq<>();

        tips.add((locale) -> ApiGateway.translate("Powered by `MindustryTool`", locale));
        tips.add((locale) -> ApiGateway.translate("Use `/discord` to join our `Discord` server", locale));
        tips.add((locale) -> ApiGateway.translate("Use `/vnw` to skip a wave", locale));
        tips.add((locale) -> ApiGateway.translate("Use `/rtv` to change map", locale));

        var tip = tips.random();

        backgroundTask("Send tip", () -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                for (var player : players) {
                    player.sendMessage("[accent]" + tip.get(locale));
                }
            });
        });
    }
}
