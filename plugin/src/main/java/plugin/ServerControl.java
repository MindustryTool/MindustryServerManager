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
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import plugin.handler.ApiGateway;
import plugin.handler.EventHandler;
import plugin.handler.HttpServer;
import plugin.handler.HubHandler;
import plugin.handler.VoteHandler;
import plugin.menus.PluginMenu;
import plugin.utils.AdminUtils;
import plugin.utils.Utils;
import plugin.handler.SessionHandler;
import plugin.handler.SnapshotHandler;
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
    public static final ExecutorService BACKGROUND_TASK_EXECUTOR = Executors.newWorkStealingPool();
    public static final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

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

        if (Config.IS_HUB) {
            HubHandler.init();
        }

        BACKGROUND_SCHEDULER.schedule(ServerControl::autoHost, 60, TimeUnit.SECONDS);
        BACKGROUND_SCHEDULER.schedule(ServerControl::autoPause, 10, TimeUnit.SECONDS);
        BACKGROUND_SCHEDULER.scheduleWithFixedDelay(ServerControl::sendTips, 0, 3, TimeUnit.MINUTES);

        PluginEvents.on(ServerLoadEvent.class, event -> isUnloaded = false);
        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = ApiGateway.translate(locale, "[scarlet]", "@Server controller restarted");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });
        Call.sendMessage("[scarlet]Server controller restarted");
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
        Log.info("Unload");

        PluginEvents.fire(new PluginUnloadEvent());
        PluginEvents.unregister();

        isUnloaded = true;

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

        tips.add((locale) -> ApiGateway.translate(locale, "@Powered by ", "MindustryTool"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Use ", "/discord", " @to join our Discord server"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Use ", "/vnw", " @to skip a wave"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Use ", "/rtv", " @to change map"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Use ", "/me", " @to see your stats"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Use ", "/grief", " @to report a player"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Use ", "/website",
                "@to visit our website for schematics and maps"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Remember to respect other players"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Remember to download and update", "MindustryTool"));
        tips.add((locale) -> ApiGateway.translate(locale, "@If you find this helpful please give us a star: ",
                Config.GITHUB_URL));
        tips.add((locale) -> ApiGateway.translate(locale, "@Be respectful — toxic behavior may lead to punishment"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Report griefers instead of arguing in chat"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Admins are here to help — ask nicely"));
        tips.add((locale) -> ApiGateway.translate(locale, Iconc.blockRouter +"Router chains"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Have fun!!!"));
        tips.add((locale) -> ApiGateway.translate(locale, "@The factory must grow!!!"));
        tips.add((locale) -> ApiGateway.translate(locale, "@Reach level ", Config.COLOR_NAME_LEVEL,
                " @to unlock colored name"));
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
