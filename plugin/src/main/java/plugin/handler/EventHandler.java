package plugin.handler;

import java.util.concurrent.TimeUnit;

import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayerBanEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.UnitBulletDestroyEvent;
import mindustry.game.EventType.WorldLoadEndEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.Config;
import plugin.PluginEvents;
import plugin.Control;
import plugin.event.PlayerKillUnitEvent;
import plugin.event.SessionCreatedEvent;
import plugin.event.SessionRemovedEvent;
import plugin.menus.RateMapMenu;
import plugin.menus.WelcomeMenu;
import dto.PlayerDto;
import plugin.utils.Utils;
import plugin.Registry;
import plugin.annotations.Component;
import plugin.annotations.Init;
import mindustry.ui.dialogs.LanguageDialog;
import java.time.Instant;

import events.ServerEvents;
import plugin.service.SessionService;

@Component
public class EventHandler {

    private final HttpServer httpServer;
    private final ApiGateway apiGateway;
    private final SessionService sessionService;

    public EventHandler(HttpServer httpServer, ApiGateway apiGateway, SessionService sessionService) {
        this.httpServer = httpServer;
        this.apiGateway = apiGateway;
        this.sessionService = sessionService;
    }

    @Init
    public void init() {
        PluginEvents.on(SessionCreatedEvent.class, this::onSessionCreatedEvent);
        PluginEvents.on(SessionRemovedEvent.class, this::onRemovedEvent);
        PluginEvents.on(PlayerChatEvent.class, this::onPlayerChat);
        PluginEvents.on(GameOverEvent.class, this::onGameOver);
        PluginEvents.on(WorldLoadEndEvent.class, this::onWorldLoadEnd);
        PluginEvents.on(PlayerConnect.class, this::onPlayerConnect);
        PluginEvents.on(UnitBulletDestroyEvent.class, this::onUnitBulletDestroy);
        PluginEvents.on(PlayerLeave.class, this::onPlayerLeave);
        PluginEvents.on(PlayerBanEvent.class, this::onPlayerBan);
    }

    private void onPlayerBan(PlayerBanEvent event) {
        String message = Strings.format("[scarlet]Player @ has been banned", event.player.name);

        httpServer.fire(ServerEvents.LogEvent.info(Control.SERVER_ID, message));
        httpServer.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, message));
    }

    private void onPlayerLeave(PlayerLeave event) {
        if (event.player.con != null && event.player.con.kicked) {
            String message = Strings.format("[scarlet]Player @ has been kicked", event.player.name);

            httpServer.fire(ServerEvents.LogEvent.info(Control.SERVER_ID, message));
            httpServer.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, message));
        }
    }

    private void onUnitBulletDestroy(UnitBulletDestroyEvent event) {
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
            Registry.get(SnapshotHandler.class).getBuiltBy(building)
                    .ifPresent(buildBy -> PluginEvents.fire(new PlayerKillUnitEvent(buildBy, unit)));
        } else if (bullet.owner() instanceof Player player) {
            PluginEvents.fire(new PlayerKillUnitEvent(player, unit));
        }
    }

    private void onGameOver(GameOverEvent event) {
        var rateMap = Vars.state.map;

        if (rateMap != null) {
            Registry.get(SessionHandler.class).each(session -> new RateMapMenu().send(session, rateMap));
        }
    }

    private void onWorldLoadEnd(WorldLoadEndEvent event) {
        Control.BACKGROUND_SCHEDULER.schedule(() -> {
            if (!Vars.state.isPaused() && Groups.player.size() == 0) {
                Vars.state.set(State.paused);
                Log.info("No player: paused");
            }

            var currentMap = Vars.state.map;

            if (currentMap != null) {
                Call.sendMessage(MapRating.getDisplayString(currentMap));
            }

        }, 5, TimeUnit.SECONDS);

        Control.cpuTask("update map preview", () -> {
            Utils.mapPreview();
        });
    }

    private void onPlayerConnect(PlayerConnect event) {
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

    private void onPlayerChat(PlayerChatEvent event) {
        Player player = event.player;
        String message = event.message;

        String chat = Strings.format("[@] => @", player.plainName(), message);

        // Filter all commands
        if (message.startsWith("/")) {
            return;
        }

        httpServer.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, chat));

        Log.info(chat);

        Control.ioTask("Chat Event", () -> {
            try {
                Utils.forEachPlayerLocale((locale, ps) -> {
                    var result = apiGateway.translateRaw(locale, Strings.stripColors(message));

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

                        httpServer.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, translatedChat));
                    }
                });
            } catch (Throwable e) {
                Log.err("Failed to send chat event", e);
            }
        });
    }

    private void onRemovedEvent(SessionRemovedEvent event) {
        try {
            var request = PlayerDto.from(event.session.player).setJoinedAt(event.session.joinedAt);
            httpServer.fire(new ServerEvents.PlayerLeaveEvent(Control.SERVER_ID, request));
        } catch (Throwable e) {
            e.printStackTrace();
        }

        try {

            Player player = event.session.player;

            String playerName = player != null ? player.plainName() : "Unknown";
            String chat = Strings.format("@ leaved the server, current players: @", playerName,
                    Math.max(Groups.player.size() - 1, 0));

            Control.BACKGROUND_SCHEDULER.schedule(() -> {
                if (!Vars.state.isPaused() && Groups.player.size() == 0) {
                    Vars.state.set(State.paused);
                    Log.info("No player: paused");
                }
            }, 5, TimeUnit.SECONDS);

            httpServer.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, chat));

            Log.info(chat);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void onSessionCreatedEvent(SessionCreatedEvent event) {
        try {
            if (Vars.state.isPaused()) {
                Vars.state.set(State.playing);
                Log.info("Player join: unpaused");
            }

            var session = event.session;

            httpServer.fire(new ServerEvents.PlayerJoinEvent(Control.SERVER_ID,
                    PlayerDto.from(session.player).setJoinedAt(Instant.now().toEpochMilli())));

            String playerName = session.player != null ? session.player.plainName() : "Unknown";
            String chat = Strings.format("@ joined the server, current players: @", playerName, Groups.player.size());

            httpServer.fire(new ServerEvents.ChatEvent(Control.SERVER_ID, chat));

            Control.ioTask("Player Join", () -> {
                var playerData = apiGateway.login(session.player);

                sessionService.setAdmin(session, playerData.getIsAdmin());

                var isLoggedIn = playerData.getLoginLink() == null;

                if (isLoggedIn) {
                    Log.info(playerData);
                    session.player.sendMessage(I18n.t(session.locale,
                            "@Logged in as ", playerData.getName()));
                } else {
                    session.player.sendMessage(I18n.t(session.locale,
                            "@You are not logged in, consider log in via ", " MindustryTool ", "@using",
                            " [accent]/login[]"));
                }
            });

            Control.ioTask("Welcome Message", () -> {
                var translated = I18n.t(session.locale, Config.WELCOME_MESSAGE);
                session.player.sendMessage(translated);
            });

            new WelcomeMenu().send(session, null);

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
