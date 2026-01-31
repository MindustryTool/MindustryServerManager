package plugin.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.UnitBulletDestroyEvent;
import mindustry.game.EventType.WorldLoadEndEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.Config;
import plugin.PluginEvents;
import plugin.ServerController;
import plugin.event.PlayerKillUnitEvent;
import plugin.menus.HubMenu;
import plugin.menus.RateMapMenu;
import plugin.menus.ServerRedirectMenu;
import plugin.menus.WelcomeMenu;
import plugin.type.PaginationRequest;
import dto.PlayerDto;
import plugin.type.ServerCore;
import plugin.utils.ServerUtils;
import plugin.utils.Utils;
import events.ServerEvents;
import dto.ServerDto;
import dto.ServerStatus;
import mindustry.net.Administration.PlayerInfo;
import mindustry.ui.dialogs.LanguageDialog;
import java.time.Instant;

public class EventHandler {

    private static final List<ServerCore> serverCores = new ArrayList<>();
    private static List<ServerDto> servers = new ArrayList<>();

    public static void init() {
        Log.info("Setup event handler");

        if (Config.IS_HUB) {
            setupCustomServerDiscovery();

            ServerController.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
                refreshServerList();
            }, 0, 30, TimeUnit.SECONDS);

            ServerController.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
                renderServerLabels();
            }, 0, 5, TimeUnit.SECONDS);
        }

        PluginEvents.on(PlayerJoin.class, EventHandler::onPlayerJoin);
        PluginEvents.on(PlayerLeave.class, EventHandler::onPlayerLeave);
        PluginEvents.on(PlayerChatEvent.class, EventHandler::onPlayerChat);
        PluginEvents.on(ServerLoadEvent.class, EventHandler::onServerLoad);
        PluginEvents.on(PlayerConnect.class, EventHandler::onPlayerConnect);
        PluginEvents.on(TapEvent.class, EventHandler::onTap);
        PluginEvents.on(GameOverEvent.class, EventHandler::onGameOver);
        PluginEvents.on(WorldLoadEndEvent.class, EventHandler::onWorldLoadEnd);
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
        ServerController.BACKGROUND_SCHEDULER.schedule(() -> {
            if (!Vars.state.isPaused() && Groups.player.size() == 0) {
                Vars.state.set(State.paused);
                Log.info("No player: paused");
            }

            var currentMap = Vars.state.map;

            if (currentMap != null) {
                Call.sendMessage(MapRating.getDisplayString(currentMap));
            }

        }, 10, TimeUnit.SECONDS);

        if (plugin.Config.IS_HUB) {
            serverCores.clear();

            var cores = Team.sharded.cores();

            for (int i = 0; i < cores.size; i++) {
                var core = cores.get(i);

                if (i < servers.size()) {
                    serverCores.add(new ServerCore(servers.get(i), core.getX(), core.getY()));
                }
            }
        }
    }

    private static void onTap(TapEvent event) {
        if (!plugin.Config.IS_HUB) {
            return;
        }

        if (event.tile == null) {
            return;
        }

        var map = Vars.state.map;

        if (map == null) {
            return;
        }

        var tapSize = 4;

        var tapX = event.tile.x;
        var tapY = event.tile.y;

        for (var core : serverCores) {
            if (tapX >= core.getX() - tapSize //
                    && tapX <= core.getX() + tapSize //
                    && tapY >= core.getY() - tapSize
                    && tapY <= core.getY() + tapSize//
            ) {
                ServerUtils.redirect(event.player, core.getServer());
            }
        }
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

    private static void setupCustomServerDiscovery() {
        // try {
        // var providerField = Net.class.getDeclaredField("provider");
        // providerField.setAccessible(true);
        // var provider = (ArcNetProvider) providerField.get(Vars.net);
        // var serverField = ArcNetProvider.class.getDeclaredField("server");
        // serverField.setAccessible(true);
        // var server = (Server) serverField.get(provider);

        // server.setDiscoveryHandler((address, handler) -> {
        // String name = mindustry.net.Administration.Config.serverName.string();
        // String description = mindustry.net.Administration.Config.desc.string();
        // String map = Vars.state.map.name();

        // ByteBuffer buffer = ByteBuffer.allocate(500);

        // int players = Groups.player.size();

        // if (Config.IS_HUB) {
        // try {
        // var serverData = getTopServer();
        // if (serverData != null) {
        // name += " -> " + serverData.name;
        // description += " -> " + serverData.description;
        // map = serverData.mapName == null ? "" : serverData.mapName;
        // players = (int) serverData.players;
        // }
        // } catch (Throwable e) {
        // e.printStackTrace();
        // }
        // }

        // writeString(buffer, name, 100);
        // writeString(buffer, map, 64);

        // buffer.putInt(Core.settings.getInt("totalPlayers", players));
        // buffer.putInt(Vars.state.wave);
        // buffer.putInt(Version.build);
        // writeString(buffer, Version.type);

        // buffer.put((byte) Vars.state.rules.mode().ordinal());
        // buffer.putInt(Vars.netServer.admins.getPlayerLimit());

        // writeString(buffer, description, 100);
        // if (Vars.state.rules.modeName != null) {
        // writeString(buffer, Vars.state.rules.modeName, 50);
        // }
        // buffer.position(0);
        // handler.respond(buffer);

        // buffer.clear();
        // });

        // } catch (Throwable e) {
        // Log.err(e);
        // }
    }

    // private void writeString(ByteBuffer buffer, String string) {
    // writeString(buffer, string, 32);
    // }

    // private void writeString(ByteBuffer buffer, String string, int maxlen) {
    // byte[] bytes = string.getBytes(Vars.charset);
    // if (bytes.length > maxlen) {
    // bytes = Arrays.copyOfRange(bytes, 0, maxlen);
    // }

    // buffer.put((byte) bytes.length);
    // buffer.put(bytes);
    // }

    private static void onServerLoad(ServerLoadEvent event) {
        Config.isLoaded = true;
    }

    private static void onPlayerChat(PlayerChatEvent event) {
        Player player = event.player;
        String message = event.message;

        String chat = Strings.format("[@] => @", player.plainName(), message);

        // Filter all commands
        if (message.startsWith("/")) {
            return;
        }

        HttpServer.fire(new ServerEvents.ChatEvent(ServerController.SERVER_ID, chat));

        Log.info(chat);

        ServerController.backgroundTask("Chat Event", () -> {
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

                        HttpServer.fire(new ServerEvents.ChatEvent(ServerController.SERVER_ID, translatedChat));
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

            HttpServer.fire(new ServerEvents.PlayerLeaveEvent(ServerController.SERVER_ID, request));
        } catch (Throwable e) {
            e.printStackTrace();
        }

        try {

            Player player = event.player;

            SessionHandler.remove(player);
            VoteHandler.removeVote(player);

            String playerName = event.player != null ? event.player.plainName() : "Unknown";
            String chat = Strings.format("@ leaved the server, current players: @", playerName,
                    Math.max(Groups.player.size() - 1, 0));

            ServerController.BACKGROUND_SCHEDULER.schedule(() -> {
                if (!Vars.state.isPaused() && Groups.player.size() == 0) {
                    Vars.state.set(State.paused);
                    Log.info("No player: paused");
                }
            }, 5, TimeUnit.SECONDS);

            HttpServer.fire(new ServerEvents.ChatEvent(ServerController.SERVER_ID, chat));

            Log.info(chat);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static synchronized ServerDto getTopServer() {
        try {
            var request = new PaginationRequest().setPage(0).setSize(1);
            var servers = ApiGateway.getServers(request);

            if (servers.isEmpty()) {
                return null;
            }

            if (servers.get(0).getId() == null) {
                return null;
            }

            return servers.get(0);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void onPlayerJoin(PlayerJoin event) {
        try {
            if (Vars.state.isPaused()) {
                Vars.state.set(State.playing);
                Log.info("Player join: unpaused");
            }

            var player = event.player;

            SessionHandler.put(player);

            HttpServer.fire(new ServerEvents.PlayerJoinEvent(ServerController.SERVER_ID, PlayerDto.from(event.player)
                    .setJoinedAt(SessionHandler.contains(player) //
                            ? SessionHandler.get(player).get().joinedAt
                            : Instant.now().toEpochMilli())));

            if (Config.IS_HUB) {
                var serverData = getTopServer();

                if (serverData != null //
                        && !serverData.getId().equals(ServerController.SERVER_ID)
                        && serverData.getPlayers() > 0//
                ) {
                    new ServerRedirectMenu().send(player, serverData);
                }
            }

            PlayerInfo target = Vars.netServer.admins.getInfoOptional(player.uuid());

            if (target != null) {
                Vars.netServer.admins.unAdminPlayer(target.id);
            }

            String playerName = player != null ? player.plainName() : "Unknown";
            String chat = Strings.format("@ joined the server, current players: @", playerName,
                    Groups.player.size());

            // var team = player.team();
            // var request = new PlayerDto()//
            // .setName(player.coloredName())//
            // .setIp(player.ip())//
            // .setLocale(player.locale())//
            // .setUuid(player.uuid())//
            // .setTeam(new TeamDto()//
            // .setName(team.name)//
            // .setColor(team.color.toString()));
            HttpServer.fire(new ServerEvents.ChatEvent(ServerController.SERVER_ID, chat));

            ServerController.backgroundTask("Player Join", () -> {
                var playerData = ApiGateway.login(player);

                if (Config.IS_HUB) {
                    new HubMenu().send(event.player, playerData.getLoginLink());
                }

                SessionHandler.get(player).ifPresent(session -> session.setAdmin(playerData.getIsAdmin()));

                var isLoggedIn = playerData.getLoginLink() == null;
                if (isLoggedIn) {
                    player.sendMessage("Logged in as " + playerData.getName());
                } else {
                    player.sendMessage("You are not logged in, consider log in via MindustryTool using /login");
                }
            });

            Core.bundle.getLocale();

            ServerController.backgroundTask("Welcome Message", () -> {
                var translated = ApiGateway.translate(Config.WELCOME_MESSAGE, Utils.parseLocale(player.locale()));
                player.sendMessage(translated);
            });

            player.sendMessage("[cyan]Powered by MindustryTool");

            new WelcomeMenu().send(player, null);

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static void renderServerLabels() {
        var map = Vars.state.map;

        if (map == null) {
            return;
        }

        Call.label(
                Config.HUB_MESSAGE,
                5,
                map.width / 2f * Vars.tilesize,
                map.height / 2f * Vars.tilesize);

        for (var core : serverCores) {
            renderServerLabel(core);
        }
    }

    private static void renderServerLabel(ServerCore core) {
        ServerDto server = core.getServer();

        float labelX = core.getX();
        float labelY = core.getY() + 25;

        var status = server.getStatus();
        String coloredStatus = (status == ServerStatus.ONLINE || status == ServerStatus.PAUSED)
                ? "[green]" + status
                : "[red]" + status;

        var mods = new ArrayList<>(server.getMods());

        mods.removeIf(m -> m.trim().equalsIgnoreCase("mindustrytoolplugin"));

        String message = server.getName() + " (Tap core to join)\n\n" +
                "[white]Status: " + coloredStatus + "\n" +
                "[white]Players: " + server.getPlayers() + "\n" +
                "[white]Map: " + server.getMapName() + "\n" +
                "[white]Mode: " + server.getMode() + "\n" +
                "[white]Description: " + server.getDescription() + "\n" +
                (mods.isEmpty() ? "" : "[white]Mods: " + mods);

        Call.label(message, 5, labelX, labelY);
    }

    private static void refreshServerList() {
        try {
            var request = new PaginationRequest()
                    .setPage(0)
                    .setSize(40);

            servers = ApiGateway.getServers(request);

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
