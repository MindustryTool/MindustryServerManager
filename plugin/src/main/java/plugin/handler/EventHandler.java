package plugin.handler;

import java.util.concurrent.TimeUnit;

import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.UnitBulletDestroyEvent;
import mindustry.game.EventType.WorldLoadEndEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.Config;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.event.PlayerKillUnitEvent;
import plugin.menus.HubMenu;
import plugin.menus.RateMapMenu;
import plugin.menus.WelcomeMenu;
import dto.PlayerDto;
import plugin.utils.Utils;
import events.ServerEvents;
import mindustry.ui.dialogs.LanguageDialog;
import java.time.Instant;

public class EventHandler {

    public static void init() {
        Log.info("Setup event handler");

        PluginEvents.on(PlayerJoin.class, EventHandler::onPlayerJoin);
        PluginEvents.on(PlayerLeave.class, EventHandler::onPlayerLeave);
        PluginEvents.on(PlayerChatEvent.class, EventHandler::onPlayerChat);
        PluginEvents.on(GameOverEvent.class, EventHandler::onGameOver);
        PluginEvents.on(WorldLoadEndEvent.class, EventHandler::onWorldLoadEnd);
        PluginEvents.on(PlayerConnect.class, EventHandler::onPlayerConnect);
        PluginEvents.on(UnitBulletDestroyEvent.class, EventHandler::onUnitBulletDestroy);

        Log.info("Setup event handler done");
    }

    public static void unload() {
        Log.info("Event handler unloaded");
    }

    private static void onUnitBulletDestroy(UnitBulletDestroyEvent event) {
        var unit = event.unit;
        var bullet = event.bullet;

        if (unit == null || bullet == null) {
            return;
        }

        if (unit.isPlayer()) {
            return;
        }

        if (bullet.owner() == null) {
            return;
        }

        if (bullet.owner() instanceof Building building) {
            SnapshotHandler.getBuiltBy(building)
                    .ifPresent(buildBy -> PluginEvents.fire(new PlayerKillUnitEvent(buildBy, unit)));
        } else if (bullet.owner() instanceof Player player) {
            PluginEvents.fire(new PlayerKillUnitEvent(player, unit));
        }
    }

    private static void onGameOver(GameOverEvent event) {
        var rateMap = Vars.state.map;

        if (rateMap != null) {
            for (var player : Groups.player) {
                new RateMapMenu().send(player, rateMap);
            }
        }
    }

    private static void onWorldLoadEnd(WorldLoadEndEvent event) {
        ServerControl.BACKGROUND_SCHEDULER.schedule(() -> {
            if (!Vars.state.isPaused() && Groups.player.size() == 0) {
                Vars.state.set(State.paused);
                Log.info("No player: paused");
            }

            var currentMap = Vars.state.map;

            if (currentMap != null) {
                Call.sendMessage(MapRating.getDisplayString(currentMap));
            }

        }, 5, TimeUnit.SECONDS);
    }

    private static void onPlayerConnect(PlayerConnect event) {
        try {
            var player = event.player;

            for (int i = 0; i < player.name().length(); i++) {
                char ch = player.name().charAt(i);
                if (ch <= '\u001f') {
                    player.kick("Invalid name");
                }
            }

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static void onPlayerChat(PlayerChatEvent event) {
        Player player = event.player;
        String message = event.message;

        String chat = Strings.format("[@] => @", player.plainName(), message);

        // Filter all commands
        if (message.startsWith("/")) {
            return;
        }

        HttpServer.fire(new ServerEvents.ChatEvent(ServerControl.SERVER_ID, chat));

        Log.info(chat);

        ServerControl.backgroundTask("Chat Event", () -> {
            try {
                Utils.forEachPlayerLocale((locale, ps) -> {
                    var result = ApiGateway.translate(locale, Strings.stripColors(message));

                    if (result.getDetectedLanguage().getLanguage().equalsIgnoreCase(locale.getLanguage())) {
                        return;
                    }

                    String translatedChat = "[sky][" + LanguageDialog.getDisplayName(locale) + "][] "
                            + player.name + "[]: "
                            + result.getTranslatedText();

                    for (var p : ps) {
                        if (p.id == player.id) {
                            continue;
                        }

                        p.sendMessage(translatedChat + "\n");

                        HttpServer.fire(new ServerEvents.ChatEvent(ServerControl.SERVER_ID, translatedChat));
                    }
                });
            } catch (Throwable e) {
                Log.err("Failed to send chat event", e);
            }
        });
    }

    private static void onPlayerLeave(PlayerLeave event) {
        try {
            var player = event.player;
            var request = PlayerDto.from(player)
                    .setJoinedAt(SessionHandler.contains(player) //
                            ? SessionHandler.get(player).get().joinedAt
                            : Instant.now().toEpochMilli());

            HttpServer.fire(new ServerEvents.PlayerLeaveEvent(ServerControl.SERVER_ID, request));
        } catch (Throwable e) {
            e.printStackTrace();
        }

        try {

            Player player = event.player;

            VoteHandler.removeVote(player);

            String playerName = event.player != null ? event.player.plainName() : "Unknown";
            String chat = Strings.format("@ leaved the server, current players: @", playerName,
                    Math.max(Groups.player.size() - 1, 0));

            ServerControl.BACKGROUND_SCHEDULER.schedule(() -> {
                if (!Vars.state.isPaused() && Groups.player.size() == 0) {
                    Vars.state.set(State.paused);
                    Log.info("No player: paused");
                }
            }, 5, TimeUnit.SECONDS);

            HttpServer.fire(new ServerEvents.ChatEvent(ServerControl.SERVER_ID, chat));

            Log.info(chat);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static void onPlayerJoin(PlayerJoin event) {
        try {
            if (Vars.state.isPaused()) {
                Vars.state.set(State.playing);
                Log.info("Player join: unpaused");
            }

            var player = event.player;

            HttpServer.fire(new ServerEvents.PlayerJoinEvent(ServerControl.SERVER_ID,
                    PlayerDto.from(event.player).setJoinedAt(Instant.now().toEpochMilli())));

            String playerName = player != null ? player.plainName() : "Unknown";
            String chat = Strings.format("@ joined the server, current players: @", playerName, Groups.player.size());

            HttpServer.fire(new ServerEvents.ChatEvent(ServerControl.SERVER_ID, chat));

            ServerControl.backgroundTask("Player Join", () -> {
                var playerData = ApiGateway.login(player);

                if (Config.IS_HUB) {
                    new HubMenu().send(event.player, playerData.getLoginLink());
                }

                SessionHandler.get(player).ifPresent(session -> session.setAdmin(playerData.getIsAdmin()));

                var isLoggedIn = playerData.getLoginLink() == null;

                if (isLoggedIn) {
                    Log.info(playerData);
                    player.sendMessage("Logged in as " + playerData.getName());
                } else {
                    player.sendMessage("You are not logged in, consider log in via MindustryTool using /login");
                }
            });

            ServerControl.backgroundTask("Welcome Message", () -> {
                var translated = ApiGateway.translate(Config.WELCOME_MESSAGE, Utils.parseLocale(player.locale()));
                player.sendMessage(translated);
            });

            new WelcomeMenu().send(player, null);

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
