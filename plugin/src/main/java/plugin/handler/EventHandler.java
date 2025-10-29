package plugin.handler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.GameState.State;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.Config;
import plugin.ServerController;
import plugin.type.HudOption;
import dto.MindustryPlayerDto;
import plugin.type.PaginationRequest;
import dto.PlayerDto;
import plugin.type.PlayerPressCallback;
import plugin.type.ServerCore;
import dto.TeamDto;
import dto.ServerDto;
import dto.ServerStatus;
import mindustry.net.Administration.PlayerInfo;
import mindustry.world.Tile;
import mindustry.world.blocks.campaign.Accelerator;

import java.time.Duration;

public class EventHandler {

    private static List<ServerDto> servers = new ArrayList<>();
    private static final List<ServerCore> serverCores = new ArrayList<>();

    private static int page = 0;
    private static int gap = 50;
    private static int rows = 4;
    private static int size = 40;
    private static int columns = size / rows;

    private static Cache<String, String> translationCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(2))
            .maximumSize(1000)
            .build();

    private static final List<String> icons = Arrays.asList(//
            "", "", "", "", "", "", "", "", "", "", //
            "", "", "", "", "", "", "", "", "", "", //
            "", "", "", "", "", "", "", "", "", "", //
            "", "", "", "", "", "", "", "", "", "", //
            "", "", "", "", "", "", "", "", "", ""//
    );

    public static void init() {
        Log.info("Setup event handler");

        if (Config.IS_HUB) {
            setupCustomServerDiscovery();

            ServerController.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
                try {
                    var request = new PaginationRequest().setPage(page).setSize(size);

                    servers = ApiGateway.getServers(request);

                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }, 0, 30, TimeUnit.SECONDS);

            ServerController.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
                try {
                    var map = Vars.state.map;

                    serverCores.clear();

                    if (map != null) {

                        Call.label(Config.HUB_MESSAGE, 5, map.width / 2 * 8, map.height / 2 * 8);

                        for (int x = 0; x < columns; x++) {
                            for (int y = 0; y < rows; y++) {
                                var index = x + y * columns;
                                int coreX = (x - columns / 2) * gap + map.width / 2;
                                int coreY = (y - rows / 2) * gap + map.height / 2;

                                if (servers != null && index < servers.size()) {
                                    var server = servers.get(index);

                                    int offsetX = (x - columns / 2) * gap * 8;
                                    int offsetY = (y - rows / 2) * gap * 8;

                                    int messageX = map.width / 2 * 8 + offsetX;
                                    int messageY = map.height / 2 * 8 + offsetY + 25;

                                    var serverStatus = server.getStatus().equals(ServerStatus.ONLINE)
                                            || server.getStatus().equals(ServerStatus.PAUSED)
                                                    ? "[green]" + server.getStatus()
                                                    : "[red]" + server.getStatus();

                                    var mods = server.getMods();
                                    mods.removeIf(m -> m.trim().equalsIgnoreCase("mindustrytoolplugin"));

                                    String message = //
                                            String.format("%s (Tap core to join)\n\n", server.getName()) //
                                                    + String.format("[white]Status: %s\n", serverStatus)//
                                                    + String.format("[white]Players: %s\n", server.getPlayers())//
                                                    + String.format("[white]Map: %s\n", server.getMapName())//
                                                    + String.format("[white]Mode: %s\n", server.getMode())//
                                                    + String.format("[white]Description: %s\n", server.getDescription())//
                                                    + (mods.isEmpty() ? "" : String.format("[white]Mods: %s", mods));

                                    Tile tile = Vars.world.tile(coreX, coreY);

                                    if (tile != null) {
                                        if (tile.build == null || !(tile.block() instanceof Accelerator)) {
                                            tile.setBlock(Blocks.interplanetaryAccelerator, Team.sharded, 0);

                                            var block = tile.block();
                                            var build = tile.build;
                                            if (block == null || build == null)
                                                return;

                                            for (var item : Vars.content.items()) {
                                                if (block.consumesItem(item) && build.items.get(item) < 10000) {
                                                    build.items.add(item, 10000);
                                                }
                                            }
                                        }
                                        serverCores.add(new ServerCore(server, coreX, coreY));
                                    }

                                    Call.label(message, 5, messageX, messageY);
                                } else {
                                    Tile tile = Vars.world.tile(coreX, coreY);
                                    if (tile != null && tile.build != null) {
                                        tile.build.kill();
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }, 0, 5, TimeUnit.SECONDS);
        }
        Log.info("Setup event handler done");
    }

    public static void unload() {
        translationCache.invalidateAll();
        translationCache = null;

        Log.info("Event handler unloaded");
    }

    public static void onGameOver(GameOverEvent event) {
    }

    public static void onTap(TapEvent event) {
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
                onServerChoose(event.player, core.getServer().getId().toString(), core.getServer().getName());
            }
        }
    }

    private static void setName(Player player, String name, int level) {
        try {
            var icon = getIconBaseOnLevel(level);
            var newName = String.format("[white]%s [%s] %s", icon, level, name);

            if (!newName.equals(player.name)) {
                var hasLevelInName = player.name.matches("\\[\\d+\\]");

                player.name(newName);

                if (hasLevelInName) {
                    player.sendMessage(String.format("You have leveled up to level %s", level));
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static String getIconBaseOnLevel(int level) {
        var index = level / 3;

        if (index >= icons.size()) {
            index = icons.size() - 1;
        }

        return icons.get(index);
    }

    public static void onPlayerConnect(PlayerConnect event) {
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

    public static void onServerLoad(ServerLoadEvent event) {
        Config.isLoaded = true;
    }

    public static void onPlayerChat(PlayerChatEvent event) {
        try {
            Player player = event.player;
            String message = event.message;

            String chat = Strings.format("[@] => @", player.plainName(), message);

            // Filter all commands
            if (message.startsWith("/")) {
                return;
            }

            ServerController.BACKGROUND_TASK_EXECUTOR.execute(() -> {
                try {
                    ApiGateway.sendChatMessage(chat);
                } catch (Throwable e) {
                    Log.err(e);
                }
            });

            HashMap<String, List<Player>> groupByLocale = new HashMap<>();

            Groups.player.forEach(p -> groupByLocale.getOrDefault(p.locale(), new ArrayList<>()).add(p));

            groupByLocale.forEach((locale, ps) -> {
                ServerController.BACKGROUND_TASK_EXECUTOR.execute(() -> {
                    try {
                        String translatedMessage = translationCache.get(locale + message,
                                _ignore -> ApiGateway.translate(message, locale));

                        for (var p : ps) {
                            if (p.id == player.id) {
                                continue;
                            }

                            p.sendMessage("[white][Translation] " + player.name() + "[]: " + translatedMessage);
                            ApiGateway.sendChatMessage(
                                    "[white][Translation] " + player.name() + "[]: " + translatedMessage);

                        }
                    } catch (Throwable e) {
                        Log.err(e);
                    }
                });
            });
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void onPlayerLeave(PlayerLeave event) {
        try {
            var player = event.player;
            var team = player.team();
            var request = new PlayerDto()//
                    .setName(player.coloredName())//
                    .setIp(player.ip())//
                    .setLocale(player.locale())//
                    .setUuid(player.uuid())//
                    .setTeam(new TeamDto()//
                            .setName(team.name)//
                            .setColor(team.color.toString()));

            ApiGateway.sendPlayerLeave(request);
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

            Log.info(chat);

            ServerController.BACKGROUND_TASK_EXECUTOR.submit(() -> {
                ApiGateway.sendChatMessage(chat);
            });
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

    public static void onPlayerJoin(PlayerJoin event) {
        try {
            if (Vars.state.isPaused()) {
                Vars.state.set(State.playing);
                Log.info("Player join: unpaused");
            }

            var player = event.player;

            SessionHandler.put(player);

            if (Config.IS_HUB) {
                var serverData = getTopServer();

                if (serverData != null //
                        && !serverData.getId().equals(ServerController.SERVER_ID)
                        && serverData.getPlayers() > 0//
                ) {
                    var options = Arrays.asList(//
                            HudHandler.option((p, state) -> {
                                HudHandler.closeFollowDisplay(p, HudHandler.SERVER_REDIRECT);
                            }, "[red]No"),
                            HudHandler.option((p, state) -> {
                                onServerChoose(p, serverData.getId().toString(), serverData.getName());
                                HudHandler.closeFollowDisplay(p, HudHandler.SERVER_REDIRECT);
                            }, "[green]Yes"));
                    HudHandler.showFollowDisplay(player, HudHandler.SERVER_REDIRECT, "Redirect",
                            "Do you want to go to server: " + serverData.getName(), null, options);
                }
            }

            PlayerInfo target = Vars.netServer.admins.getInfoOptional(player.uuid());

            if (target != null) {
                Vars.netServer.admins.unAdminPlayer(target.id);
            }

            String playerName = player != null ? player.plainName() : "Unknown";
            String chat = Strings.format("@ joined the server, current players: @", playerName,
                    Groups.player.size());

            var team = player.team();
            var request = new PlayerDto()//
                    .setName(player.coloredName())//
                    .setIp(player.ip())//
                    .setLocale(player.locale())//
                    .setUuid(player.uuid())//
                    .setTeam(new TeamDto()//
                            .setName(team.name)//
                            .setColor(team.color.toString()));

            Log.info(chat);

            ServerController.BACKGROUND_TASK_EXECUTOR.submit(() -> {
                ApiGateway.sendChatMessage(chat);
            });

            var playerData = ApiGateway.setPlayer(request);

            if (Config.IS_HUB) {
                sendHub(event.player, playerData.getLoginLink());
            }

            setPlayerData(playerData, player);

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void sendHub(Player player, String loginLink) {
        var options = new ArrayList<HudOption>();

        if (loginLink != null && !loginLink.isEmpty()) {
            options.add(HudHandler.option((trigger, state) -> {
                Call.openURI(trigger.con, loginLink);
                HudHandler.closeFollowDisplay(trigger, HudHandler.HUB_UI);

            }, "[green]Login via MindustryTool"));
        }

        options.add(HudHandler.option((p, state) -> Call.openURI(player.con, Config.RULE_URL), "[green]Rules"));
        options.add(
                HudHandler.option((p, state) -> Call.openURI(player.con, Config.MINDUSTRY_TOOL_URL), "[green]Website"));
        options.add(
                HudHandler.option((p, state) -> Call.openURI(player.con, Config.DISCORD_INVITE_URL), "[blue]Discord"));
        options.add(HudHandler.option((p, state) -> {
            sendServerList(player, 0);
            HudHandler.closeFollowDisplay(p, HudHandler.HUB_UI);
        }, "[red]Close"));

        HudHandler.showFollowDisplay(player, HudHandler.HUB_UI, "Servers", Config.HUB_MESSAGE, null,
                options);
    }

    public static void sendServerList(Player player, int page) {
        try {
            var size = 8;
            var request = new PaginationRequest().setPage(page).setSize(size);

            var servers = ApiGateway.getServers(request);

            PlayerPressCallback invalid = (p, s) -> {
                sendServerList(p, (int) s);
                Call.infoToast(p.con, "Please don't click there", 10f);
            };

            List<List<HudOption>> options = new ArrayList<>();

            servers.stream().sorted(Comparator.comparing(ServerDto::getPlayers).reversed()).forEach(server -> {
                PlayerPressCallback valid = (p, s) -> onServerChoose(p, server.getId().toString(),
                        server.getName());

                var mods = server.getMods();
                mods.removeIf(m -> m.trim().equalsIgnoreCase("mindustrytoolplugin"));

                if (server.getMapName() == null) {
                    options.add(Arrays.asList(HudHandler.option(valid, String.format("[yellow]%s", server.getName())),
                            HudHandler.option(valid, "[scarlet]Server offline.")));
                } else {
                    options.add(Arrays.asList(HudHandler.option(valid, server.getName()),
                            HudHandler.option(valid, String.format("[lime]Players:[] %d", server.getPlayers()))));

                    options.add(Arrays.asList(
                            HudHandler.option(valid,
                                    String.format("[cyan]Gamemode:[] %s", server.getMode().toLowerCase())),
                            HudHandler.option(valid, String.format("[blue]Map:[] %s", server.getMapName()))));
                }

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    options.add(Arrays.asList(HudHandler.option(valid,
                            String.format("[purple]Mods:[] %s", String.join(", ", mods)))));
                }

                if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                    options.add(Arrays
                            .asList(HudHandler.option(valid, String.format("[grey]%s", server.getDescription()))));
                }

                options.add(Arrays.asList(HudHandler.option(invalid, "-----------------")));
            });

            options.add(Arrays.asList(page > 0 ? HudHandler.option((p, state) -> {
                sendServerList(player, (int) state - 1);
            }, "[orange]Previous") : HudHandler.option(invalid, "First page"),
                    servers.size() == size ? HudHandler.option((p, state) -> {
                        sendServerList(player, (int) state + 1);
                    }, "[lime]Next") : HudHandler.option(invalid, "No more")));

            options.add(Arrays.asList(
                    HudHandler.option(
                            (p, state) -> HudHandler.closeFollowDisplay(p, HudHandler.SERVERS_UI),
                            "[scarlet]Close")));

            HudHandler.showFollowDisplays(player, HudHandler.SERVERS_UI, "List of all servers",
                    Config.CHOOSE_SERVER_MESSAGE, Integer.valueOf(page), options);
        } catch (Throwable e) {
            Log.err(e);
        }
    }

    public static void onServerChoose(Player player, String id, String name) {
        HudHandler.closeFollowDisplay(player, HudHandler.SERVERS_UI);

        ServerController.BACKGROUND_TASK_EXECUTOR.submit(() -> {
            try {
                player.sendMessage(String.format(
                        "[green]Starting server [white]%s, [white]this can take up to 1 minutes, please wait", name));
                Log.info(String.format("Send host command to server %s %S", name, id));
                var data = ApiGateway.host(id);
                player.sendMessage("[green]Redirecting");
                Call.sendMessage(
                        String.format("%s [green]redirecting to server [white]%s, use [green]/servers[white] to follow",
                                player.coloredName(), name));

                String host = "";
                int port = 6567;

                var colon = data.lastIndexOf(":");

                if (colon > 0) {
                    host = data.substring(0, colon);
                    port = Integer.parseInt(data.substring(colon + 1).trim());
                } else {
                    host = data;
                }

                Log.info("Redirecting " + player.name + " to " + host + ":" + port);

                Call.connect(player.con, InetAddress.getByName(host.trim()).getHostAddress(), port);
            } catch (Throwable e) {
                player.sendMessage("Error: Can not load server");
                e.printStackTrace();
            }
        });
    }

    public static void setPlayerData(MindustryPlayerDto playerData, Player player) {
        var uuid = playerData.getUuid();
        var exp = playerData.getExp();
        var name = playerData.getName();
        var isLoggedIn = playerData.getLoginLink() == null;

        PlayerInfo target = Vars.netServer.admins.getInfoOptional(player.uuid());
        var isAdmin = playerData.isAdmin();

        if (uuid == null) {
            Log.warn("Player with null uuid: " + playerData);
            return;
        }

        Player playert = Groups.player.find(p -> p.getInfo() == target);

        if (target != null) {
            if (isAdmin) {
                Vars.netServer.admins.adminPlayer(target.id,
                        playert == null ? target.adminUsid : playert.usid());
            } else {
                Vars.netServer.admins.unAdminPlayer(target.id);
            }
            if (playert != null)
                playert.admin = isAdmin;
        }

        if (isLoggedIn) {
            setName(player, name, (int) Math.sqrt(exp));
            player.sendMessage("Logged in as " + name);
        } else {
            player.sendMessage("You are not logged in, consider log in via MindustryTool using /login");
        }
    }

}
