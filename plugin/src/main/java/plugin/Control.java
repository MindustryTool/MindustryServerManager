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
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import plugin.handler.ApiGateway;
import plugin.handler.HttpServer;
import plugin.handler.I18n;
import plugin.utils.Utils;
import plugin.commands.ClientCommandHandler;
import plugin.commands.ServerCommandHandler;
import plugin.database.DB;
import plugin.event.PluginUnloadEvent;
import loader.MindustryToolPlugin;

public class Control extends Plugin implements MindustryToolPlugin {

    public static PluginState state = PluginState.LOADING;

    public static final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));

    public static final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    private static final ExecutorService IO_TASK_EXECUTOR = Executors
            .newCachedThreadPool();

    private static final ExecutorService CPU_TASK_EXECUTOR = Executors
            .newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public Control() {
        Log.info("Server controller created: " + this);
    }

    @Override
    public void start() {
        Log.info("Server controller started: " + this);
    }

    @Override
    public void init() {
        Registry.init(getClass().getPackage().getName());

        DB.init();

        BACKGROUND_SCHEDULER.schedule(this::autoHost, 60, TimeUnit.SECONDS);
        BACKGROUND_SCHEDULER.schedule(this::autoPause, 10, TimeUnit.SECONDS);
        BACKGROUND_SCHEDULER.scheduleWithFixedDelay(Control::sendTips, 3, 3, TimeUnit.MINUTES);

        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = "[scarlet]" + I18n.t(locale, "@Server controller restarted");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });

        state = PluginState.LOADED;
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        Core.app.post(() -> Registry.get(ServerCommandHandler.class).registerCommands(handler));
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        Core.app.post(() -> Registry.get(ClientCommandHandler.class).registerCommands(handler));
    }

    @Override
    public void onEvent(Object event) {
        try {
            if (state != PluginState.LOADED) {
                return;
            }

            PluginEvents.fire(event);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void unload() {
        state = PluginState.UNLOADED;

        Log.info("Unload");

        IO_TASK_EXECUTOR.shutdownNow();
        Log.info("Background task executor shutdown");
        BACKGROUND_SCHEDULER.shutdownNow();
        Log.info("Background scheduler shutdown");

        PluginEvents.fire(new PluginUnloadEvent());
        Registry.destroy();
        PluginEvents.unregister();

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

    public static void ioTask(String name, Runnable r) {
        if (IO_TASK_EXECUTOR.isShutdown()) {
            return;
        }

        var result = IO_TASK_EXECUTOR.submit(() -> {
            long startedAt = Time.millis();
            try {
                r.run();
            } catch (Exception e) {
                Log.err("Failed to execute io task: " + name, e);
            } finally {
                var elapsed = Time.millis() - startedAt;
                if (elapsed > 1000) {
                    Log.warn("IO task " + name + " took " + elapsed + "ms");
                }
            }
        });

        BACKGROUND_SCHEDULER.schedule(() -> {
            if (!result.isDone()) {
                Log.warn("[orange]IO task " + name + " timed out");
                result.cancel(true);
            }
        }, 5, TimeUnit.SECONDS);
    }

    public static void cpuTask(String name, Runnable r) {
        if (CPU_TASK_EXECUTOR.isShutdown()) {
            return;
        }

        CPU_TASK_EXECUTOR.submit(() -> {
            long startedAt = Time.millis();
            try {
                r.run();
            } catch (Exception e) {
                Log.err("Failed to execute cpu task: " + name, e);
            } finally {
                var elapsed = Time.millis() - startedAt;
                if (elapsed > 1000) {
                    Log.warn("CPU task " + name + " took " + elapsed + "ms");
                }
            }
        });
    }

    private void autoHost() {
        try {
            if (!Vars.state.isGame()) {
                Log.info("Server not hosting, auto host");
                Registry.get(ApiGateway.class).host(SERVER_ID.toString());
            }
        } catch (Exception e) {
            Log.err("Failed to host server", e);
        }
    }

    private void autoPause() {
        if (!Vars.state.isPaused() && Groups.player.size() == 0) {
            Vars.state.set(State.paused);
            Log.info("No player: paused");
        }

        var httpServer = Registry.get(HttpServer.class);
        if (httpServer != null && httpServer.isConnected()) {
            Registry.get(ApiGateway.class).requestConnection();
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
        tips.add((locale) -> "The factory must grow!!!");
        tips.add((locale) -> I18n.t(locale, "@Reach level", " ", Config.COLOR_NAME_LEVEL, " ",
                "@to unlock colored name"));

        var tip = tips.random();

        ioTask("Send tip", () -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                for (var player : players) {
                    player.sendMessage("\n[sky]" + tip.get(locale) + "[]\n");
                }
            });
        });
    }
}
