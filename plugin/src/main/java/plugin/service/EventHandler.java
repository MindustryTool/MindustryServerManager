package plugin.service;

import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayerBanEvent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.WorldLoadEndEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.Cfg;
import plugin.Control;
import plugin.event.SessionCreatedEvent;
import plugin.event.SessionRemovedEvent;
import plugin.menus.RateMapMenu;
import plugin.menus.WelcomeMenu;
import dto.PlayerDto;
import plugin.utils.Utils;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.core.Registry;
import java.time.Instant;

import events.ServerEvents;

@Component
public class EventHandler {

    private final ApiGateway apiGateway;
    private final SessionService sessionService;

    public EventHandler(ApiGateway apiGateway, SessionService sessionService) {
        this.apiGateway = apiGateway;
        this.sessionService = sessionService;
    }

    @Init
    private void init() {
        Vars.netServer.admins.addChatFilter((player, message) -> {
            String chat = Strings.format("[@]: @", player.plainName(), message);
            String coloredMessage = Strings.format("[@]:[#ffffff] @", player.name(), message);

            player.sendMessage(coloredMessage);
            Log.info(coloredMessage);

            apiGateway.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, chat));

            Tasks.io("Chat Event", () -> {
                try {
                    Utils.forEachPlayerLocale((locale, players) -> {
                        String strippedMessage = Strings.stripColors(message);
                        var result = apiGateway.translateRaw(locale, strippedMessage);

                        for (var p : players) {
                            if (p.id == player.id) {
                                continue;
                            }

                            p.sendMessage(coloredMessage + " [gray](" + result + ")", player);
                        }
                    });
                } catch (Exception e) {
                    Log.err("Failed to send chat event", e);
                }
            });

            return null;
        });
    }

    @Listener
    private void onPlayerBan(PlayerBanEvent event) {
        String message = Strings.format("[scarlet]Player @ has been banned", event.player.name);

        apiGateway.fire(ServerEvents.LogEvent.info(Control.SERVER_ID, message));
        apiGateway.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, message));
    }

    @Listener
    private void onPlayerLeave(PlayerLeave event) {
        if (event.player.con != null && event.player.con.kicked) {
            String message = Strings.format("[scarlet]Player @ has been kicked", event.player.name);

            apiGateway.fire(ServerEvents.LogEvent.info(Control.SERVER_ID, message));
            apiGateway.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, message));
        }
    }

    @Listener
    private void onGameOver(GameOverEvent event) {
        var rateMap = Vars.state.map;

        if (rateMap != null) {
            Registry.get(SessionHandler.class).each(session -> new RateMapMenu().send(session, rateMap));
        }
    }

    @Listener
    private void onWorldLoadEnd(WorldLoadEndEvent event) {
        var currentMap = Vars.state.map;

        if (currentMap != null) {
            Call.sendMessage(MapRating.getDisplayString(currentMap));
        }

        Tasks.io("update map preview", () -> {
            Utils.generateMapPreview();
        });
    }

    @Listener
    private void onPlayerConnect(PlayerConnect event) {
        try {
            var player = event.player;

            for (int i = 0; i < player.name().length(); i++) {
                char ch = player.name().charAt(i);
                if (ch <= '\u001f') {
                    player.kick("Invalid name");
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Listener
    private void onRemovedEvent(SessionRemovedEvent event) {
        try {
            var request = PlayerDto.from(event.session.player).setJoinedAt(event.session.joinedAt);
            apiGateway.fire(new ServerEvents.PlayerLeaveEvent(Control.SERVER_ID, request));
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            Player player = event.session.player;

            String playerName = player != null ? player.plainName() : "Unknown";
            String chat = Strings.format("@ leaved the server, current players: @", playerName,
                    Math.max(Groups.player.size() - 1, 0));

            apiGateway.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, chat));

            Log.info(chat);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Listener
    private void onSessionCreatedEvent(SessionCreatedEvent event) {
        try {
            if (Vars.state.isPaused()) {
                Vars.state.set(State.playing);
                Log.info("Player join: unpaused");
            }

            var session = event.session;

            apiGateway.fire(new ServerEvents.PlayerJoinEvent(Control.SERVER_ID,
                    PlayerDto.from(session.player).setJoinedAt(Instant.now().toEpochMilli())));

            String playerName = session.player != null ? session.player.plainName() : "Unknown";
            String chat = Strings.format("@ joined the server, current players: @", playerName, Groups.player.size());

            apiGateway.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, chat));

            Tasks.io("Player Join", () -> {
                var playerData = apiGateway.login(session.player);

                sessionService.setLogin(session, playerData);

                var isLoggedIn = playerData.getLoginLink() == null;

                if (isLoggedIn) {
                    Log.info(playerData);
                    session.player.sendMessage(I18n.t(session.locale,
                            "@Logged in as ", playerData.getName()));
                } else {
                    session.player.sendMessage(I18n.t(session.locale,
                            "@You are not logged in, consider log in via ", " MindustryTool ", "@using",
                            " [accent]/login[white]"));
                }
            });

            Tasks.io("Welcome Message", () -> {
                var translated = I18n.t(session.locale, Cfg.WELCOME_MESSAGE);
                session.player.sendMessage(translated);
            });

            new WelcomeMenu().send(session, null);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
