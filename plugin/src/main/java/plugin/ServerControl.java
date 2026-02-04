package plugin;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.pf4j.Plugin;

import arc.Core;
import arc.func.Func;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import plugin.handler.ApiGateway;
import plugin.handler.EventHandler;
import plugin.handler.HttpServer;
import plugin.handler.HubHandler;
import plugin.handler.I18n;
import plugin.handler.VoteHandler;
import plugin.menus.PluginMenu;
import plugin.utils.AdminUtils;
import plugin.utils.Utils;
import plugin.handler.SessionHandler;
import plugin.handler.SnapshotHandler;
import plugin.handler.TrailHandler;
import plugin.repository.SessionRepository;
import plugin.workflow.Workflow;
import plugin.commands.ClientCommandHandler;
import plugin.commands.ServerCommandHandler;
import plugin.database.DB;
import plugin.event.PluginUnloadEvent;
import loader.MindustryToolPlugin;

public class ServerControl extends Plugin implements MindustryToolPlugin {

    public static boolean isUnloaded = false;

    public static final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));
    public static final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService BACKGROUND_TASK_EXECUTOR = Executors.newWorkStealingPool();
    private static final Seq<String> runningTasks = new Seq<>();

    public ServerControl() {
        Log.info("Server controller created: " + this);
    }

    @Override
    public void start() {
        Log.info("Server controller started: " + this);
    }

    @Override
    public void init() {
        DB.init();
        HttpServer.init();
        Workflow.init();
        EventHandler.init();
        ApiGateway.init();
        PluginMenu.init();
        SessionRepository.init();
        SessionHandler.init();
        SnapshotHandler.init();
        AdminUtils.init();
        VoteHandler.init();
        TrailHandler.init();

        if (Config.IS_HUB) {
            HubHandler.init();
        }

        BACKGROUND_SCHEDULER.schedule(ServerControl::autoHost, 60, TimeUnit.SECONDS);
        BACKGROUND_SCHEDULER.schedule(ServerControl::autoPause, 10, TimeUnit.SECONDS);
        BACKGROUND_SCHEDULER.scheduleWithFixedDelay(ServerControl::sendTips, 3, 3, TimeUnit.MINUTES);

        PluginEvents.on(ServerLoadEvent.class, event -> isUnloaded = false);
        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = "[scarlet]" + I18n.t(locale, "@Server controller restarted");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        Core.app.post(() -> ServerCommandHandler.registerCommands(handler));
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        Core.app.post(() -> ClientCommandHandler.registerCommands(handler));
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

        Log.info("Unload");

        PluginEvents.fire(new PluginUnloadEvent());
        PluginEvents.unregister();

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

    public static synchronized void backgroundTask(String name, Runnable r) {
        runningTasks.add(name);
        Log.info("Running tasks: " + runningTasks);
        BACKGROUND_TASK_EXECUTOR.submit(() -> {
            try {
                r.run();
            } catch (Exception e) {
                Log.err("Failed to execute background task: " + name, e);
            } finally {
                runningTasks.remove(name);
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

        tips.add((locale) -> I18n.t(locale, "@Powered by", " MindustryTool"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/discord[sky] ",
                "@to join our Discord server"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/vnw[sky] ", "@to skip a wave"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/rtv[sky] ", "@to change map"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/me[sky] ", "@to see your stats"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/grief[sky] ", "@to report a player"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/website[sky] ",
                "@to visit our website for schematics and maps"));
        tips.add((locale) -> I18n.t(locale, "@Remember to respect other players"));
        tips.add((locale) -> I18n.t(locale, "@Remember to download and update", " MindustryTool"));
        tips.add((locale) -> I18n.t(locale, "@If you find this helpful please give us a star: ",
                Config.GITHUB_URL));
        tips.add((locale) -> I18n.t(locale, "@Be respectful — toxic behavior may lead to punishment"));
        tips.add((locale) -> I18n.t(locale, "@Report griefers instead of arguing in chat"));
        tips.add((locale) -> I18n.t(locale, "@Admins are here to help — ask nicely"));
        tips.add((locale) -> "[]" + Iconc.blockRouter + "Router chains");
        tips.add((locale) -> I18n.t(locale, "@Have fun!!!"));
        tips.add((locale) -> I18n.t(locale, "@The factory must grow!!!"));
        tips.add((locale) -> I18n.t(locale, "@Reach level", " ", Config.COLOR_NAME_LEVEL, " ",
                "@to unlock colored name"));

        var tip = tips.random();

        backgroundTask("Send tip", () -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                for (var player : players) {
                    player.sendMessage("\n[sky]" + tip.get(locale) + "[]\n");
                }
            });
        });
    }
}
