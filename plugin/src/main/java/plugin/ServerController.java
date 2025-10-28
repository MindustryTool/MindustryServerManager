package plugin;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.pf4j.Plugin;

import arc.util.*;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.MenuOptionChooseEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.EventType.StateChangeEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.WorldLoadEvent;
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

    public static final ExecutorService BACKGROUND_TASK_EXECUTOR = new ThreadPoolExecutor(
            0,
            20,
            5,
            TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>());

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
        EventHandler.init();
        ApiGateway.init();
        Workflow.init();

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
            Workflow.fire(event, true);

            if (event instanceof PlayerJoin playerJoin) {
                EventHandler.onPlayerJoin(playerJoin);
                HttpServer.sendStateUpdate();
            } else if (event instanceof PlayerLeave playerLeave) {
                EventHandler.onPlayerLeave(playerLeave);
                HudHandler.onPlayerLeave(playerLeave);
                HttpServer.sendStateUpdate();
            } else if (event instanceof PlayerChatEvent playerChat) {
                EventHandler.onPlayerChat(playerChat);
            } else if (event instanceof ServerLoadEvent serverLoad) {
                EventHandler.onServerLoad(serverLoad);
            } else if (event instanceof PlayerConnect playerConnect) {
                EventHandler.onPlayerConnect(playerConnect);
            } else if (event instanceof TapEvent tapEvent) {
                EventHandler.onTap(tapEvent);
            } else if (event instanceof MenuOptionChooseEvent menuOption) {
                HudHandler.onMenuOptionChoose(menuOption);
            } else if (event instanceof GameOverEvent gameOverEvent) {
                EventHandler.onGameOver(gameOverEvent);
            } else if (event instanceof WorldLoadEvent) {
                HttpServer.sendStateUpdate();
            } else if (event instanceof StateChangeEvent) {
                HttpServer.sendStateUpdate();
            }

            Workflow.fire(event, false);
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
        HttpServer.unload();
        ApiGateway.unload();
        Workflow.clear();

        EventHandler.unload();
        HudHandler.unload();
        VoteHandler.unload();

        BACKGROUND_TASK_EXECUTOR.shutdownNow();
        BACKGROUND_SCHEDULER.shutdownNow();


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
