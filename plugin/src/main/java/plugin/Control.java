package plugin;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.Events;
import arc.func.Func;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import plugin.service.ApiGateway;
import plugin.service.I18n;
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
        Core.app.post(() -> Registry.get(ServerCommandHandler.class).registerCommands(handler));
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        Core.app.post(() -> Registry.get(ClientCommandHandler.class).registerCommands(handler));
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

    @Schedule(delay = 5, unit = TimeUnit.SECONDS)
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

    @Schedule(delay = 5, fixedDelay = 10, unit = TimeUnit.SECONDS)
    private void autoPause() {
        if (!Vars.state.isPaused() && Groups.player.size() == 0) {
            Vars.state.set(State.paused);
            Log.info("No player: paused");
        }
    }

    @Schedule(fixedDelay = 3, unit = TimeUnit.MINUTES)
    private void sendTips() {
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
        tips.add((locale) -> "[white]" + Iconc.blockRouter + "Router chains");
        tips.add((locale) -> I18n.t(locale, "@Have fun!!!"));
        tips.add((locale) -> "The factory must grow!!!");
        tips.add((locale) -> I18n.t(locale, "@Reach level", " ", Config.COLOR_NAME_LEVEL, " ",
                "@to unlock colored name"));

        var tip = tips.random();

        Tasks.io("Send tip", () -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                for (var player : players) {
                    player.sendMessage("\n[sky]" + tip.get(locale) + "[white]\n");
                }
            });
        });
    }

    private void registerEventListener() {
        for (Class<?> clazz : EventType.class.getDeclaredClasses()) {
            Events.on(clazz, this::onEvent);
        }
    }

    private void registerTriggerListener() {
        for (EventType.Trigger trigger : EventType.Trigger.values()) {
            Events.run(trigger, () -> onEvent(trigger));
        }
    }

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
}
