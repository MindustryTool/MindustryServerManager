package plugin.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.CommandHandler.CommandRunner;
import arc.util.Log;
import arc.util.Strings;
import lombok.Getter;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;
import plugin.ServerController;
import plugin.type.HudOption;
import dto.MindustryPlayerDto;
import plugin.type.PaginationRequest;
import dto.PlayerDto;
import plugin.type.PlayerPressCallback;
import dto.TeamDto;

public class ClientCommandHandler {

    private static final List<String> registeredCommands = new ArrayList<>();
    private static final boolean isPreparingForNewWave = false;
    private static short waveVoted = 0;

    @Getter
    private static CommandHandler handler;

    private static void register(String text, String params, String description, CommandRunner<Player> runner) {
        handler.register(text, params, description, runner);
        registeredCommands.add(text);
    }

    public static void unload() {
        registeredCommands.forEach(command -> handler.removeCommand(command));

        Log.info("Client command unloaded");
    }

    public static void registerCommands(CommandHandler handler) {
        ClientCommandHandler.handler = handler;

        register("rtv", "<mapId>", "Vote to change map (map id in /maps)", (args, player) -> {
            if (args.length != 1) {
                return;
            }

            int mapId;

            try {
                mapId = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                player.sendMessage("[red]Map id must be a number");
                return;
            }

            Seq<Map> maps = VoteHandler.getMaps();

            if (mapId < 0 || mapId > (maps.size - 1)) {
                player.sendMessage("[red]Invalid map id");
                return;
            }
            if (VoteHandler.isVoted(player, mapId)) {
                Call.sendMessage("[red]RTV: " + player.name + " [accent]removed their vote for [yellow]"
                        + maps.get(mapId).name());
                VoteHandler.removeVote(player, mapId);
                return;
            }
            VoteHandler.vote(player, mapId);
            Call.sendMessage("[red]RTV: [accent]" + player.name() + " [white]Want to change map to [yellow]"
                    + maps.get(mapId).name());
            Call.sendMessage("[red]RTV: [white]Current Vote for [yellow]" + maps.get(mapId).name() + "[white]: [green]"
                    + VoteHandler.getVoteCount(mapId) + "/"
                    + VoteHandler.getRequire());
            Call.sendMessage("[red]RTV: [white]Use [yellow]/rtv " + mapId + " [white]to add your vote to this map !");
            VoteHandler.check(mapId);
        });

        register("maps", "[page]", "Display available maps", (args, player) -> {
            final int MAPS_PER_PAGE = 10;
            Seq<Map> maps = VoteHandler.getMaps();
            int page = 1;
            int maxPage = maps.size / MAPS_PER_PAGE + (maps.size % MAPS_PER_PAGE == 0 ? 0 : 1);
            if (args.length == 0) {
                page = 1;

            } else if (args.length == 1) {
                try {
                    page = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage("[red]Page must be a number");
                    return;
                }
            }

            if (page < 1 || page > maxPage) {
                player.sendMessage("[red]Invalid page");
                return;
            }

            player.sendMessage("[green]Available maps: [white](" + page + "/" + maxPage + ")");

            for (int i = 0; i < MAPS_PER_PAGE; i++) {
                int mapId = (page - 1) * MAPS_PER_PAGE + i;
                if (mapId > maps.size - 1) {
                    break;
                }
                player.sendMessage("[green]" + mapId + " [white]- [yellow]" + maps.get(mapId).name());
            }
        });

        register("servers", "", "Display available servers", (args, player) -> {
            EventHandler.sendServerList(player, 0);
        });

        register("hub", "", "Display available servers", (args, player) -> {
            EventHandler.sendHub(player, null);
        });

        register("js", "<code...>", "Execute JavaScript code.", (args, player) -> {
            if (player.admin) {
                String output = Vars.mods.getScripts().runConsole(args[0]);
                player.sendMessage("> " + (isError(output) ? "[#ff341c]" + output : output));
            } else {
                player.sendMessage("[scarlet]You must be admin to use this command.");
            }
        });

        register("login", "", "Login", (args, player) -> {
            try {
                var team = player.team();
                var request = new PlayerDto()//
                        .setName(player.coloredName())//
                        .setIp(player.ip())//
                        .setUuid(player.uuid())//
                        .setTeam(new TeamDto()//
                                .setName(team.name)//
                                .setColor(team.color.toString()));

                MindustryPlayerDto playerData = ApiGateway.setPlayer(request);

                var loginLink = playerData.getLoginLink();

                if (loginLink != null && !loginLink.isEmpty()) {
                    Call.openURI(player.con, loginLink);
                } else {
                    player.sendMessage("Already logged in");
                }
            } catch (Exception e) {
                Log.err(e);
            }
        });

        register("vnw", "[number]", "Vote for sending a New Wave", (arg, player) -> {
            var session = SessionHandler.get(player);

            if (Groups.player.size() < 3 && !player.admin) {
                player.sendMessage("[scarlet]3 players are required or be an admin to start a vote.");
                return;

            } else if (session.votedVNW) {
                player.sendMessage("You have Voted already.");
                return;
            }

            if (arg.length == 1) {
                if (!isPreparingForNewWave) {
                    if (player.admin) {
                        if (Strings.canParseInt(arg[0])) {
                            waveVoted = (short) Strings.parseInt(arg[0]);
                        } else {
                            player.sendMessage("Please select number of wave want to skip");
                            return;
                        }

                    }
                } else {
                    player.sendMessage("A vote to skip wave is already in progress!");
                    return;
                }
            } else if (!isPreparingForNewWave) {
                waveVoted = 1;
            }

            session.votedVNW = true;
            int cur = SessionHandler.count(p -> p.votedVNW),
                    req = Mathf.ceil(0.6f * Groups.player.size());
            Call.sendMessage(player.name + "[orange] has voted to "
                    + (waveVoted == 1 ? "send a new wave" : "skip [green]" + waveVoted + " waves") + ". [lightgray]("
                    + (req - cur) + " votes missing)");

            if (!isPreparingForNewWave)
                ServerController.BACKGROUND_SCHEDULER.schedule(() -> {
                    Call.sendMessage("[scarlet]Vote for "
                            + (waveVoted == 1 ? "sending a new wave"
                                    : "skipping [scarlet]" + waveVoted + "[] waves")
                            + " failed! []Not enough votes.");
                    waveVoted = 0;
                }, 60, TimeUnit.SECONDS);

            if (cur < req)
                return;

            Call.sendMessage("[green]Vote for "
                    + (waveVoted == 1 ? "sending a new wave" : "skiping [scarlet]" + waveVoted + "[] waves")
                    + " is Passed. New Wave will be Spawned.");

            if (waveVoted > 0) {
                while (waveVoted-- > 0) {
                    try {
                        Vars.state.wavetime = 0f;
                        Thread.sleep(30);
                    } catch (Exception e) {
                        break;
                    }
                }

            } else
                Vars.state.wave += waveVoted;
        });

        register("redirect", "", "Redirect all player to server", (args, player) ->

        {
            if (player.admin) {
                sendRedirectServerList(player, 0);
            } else {
                player.sendMessage("[scarlet]You must be admin to use this command.");
            }
        });

    }

    private static boolean isError(String output) {
        try {
            String errorName = output.substring(0, output.indexOf(' ') - 1);
            Class.forName("org.mozilla.javascript." + errorName);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void onServerChoose(Player player, String id, String name) {
        HudHandler.closeFollowDisplay(player, HudHandler.SERVERS_UI);
        player.sendMessage(
                String.format("[green]Starting server [white]%s, [white]redirection will happen soon", name));

        try {
            ServerController.BACKGROUND_TASK_EXECUTOR.submit(() -> {
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
                    port = Integer.parseInt(data.substring(colon + 1));
                } else {
                    host = data;
                }

                final var h = host;
                final var p = port;

                Groups.player.forEach(target -> {
                    Log.info("Redirecting player " + target.name + " to " + h + ":" + p);
                    Call.connect(target.con, h, p);
                });
            });
        } catch (Exception e) {
            player.sendMessage("Error: Can not load server");
            e.printStackTrace();
        }
    }

    public static void sendRedirectServerList(Player player, int page) {
        ServerController.BACKGROUND_TASK_EXECUTOR.submit(() -> {
            try {
                var size = 8;
                var request = new PaginationRequest()//
                        .setPage(page)//
                        .setSize(size);

                var response = ApiGateway.getServers(request);
                var servers = response.getServers();

                PlayerPressCallback invalid = (p, s) -> {
                    Call.infoToast(p.con, "Please don't click there", 10f);
                    sendRedirectServerList(p, (int) s);
                };

                List<List<HudOption>> options = new ArrayList<>(Arrays.asList(
                        Arrays.asList(HudHandler.option(invalid, "[#FFD700]Server name"),
                                HudHandler.option(invalid, "[#FFD700]Players playing")),
                        Arrays.asList(HudHandler.option(invalid, "[#87CEEB]Server Gamemode"),
                                HudHandler.option(invalid, "[#FFA500]Map Playing")),
                        Arrays.asList(HudHandler.option(invalid, "[#DA70D6]Server Mods")),
                        Arrays.asList(HudHandler.option(invalid, "[#B0B0B0]Server Description"))));

                servers.forEach(server -> {
                    PlayerPressCallback valid = (p, s) -> //
                    onServerChoose(p, server.getId().toString(), server.getName());

                    options.add(Arrays.asList(HudHandler.option(invalid, "-----------------")));
                    options.add(Arrays.asList(HudHandler.option(valid, String.format("[#FFD700]%s", server.getName())),
                            HudHandler.option(valid, String.format("[#32CD32]Players: %d", server.getPlayers()))));
                    options.add(Arrays.asList(
                            HudHandler.option(valid, String.format("[#87CEEB]Gamemode: %s", server.getMode())),
                            HudHandler.option(valid, String.format("[#1E90FF]Map: %s",
                                    server.getMapName() != null ? server.getMapName() : "[#FF4500]Server offline"))));

                    if (server.getMods() != null && !server.getMods().isEmpty()) {
                        options.add(Arrays.asList(HudHandler.option(valid,
                                String.format("[#DA70D6]Mods: %s", String.join(", ", server.getMods())))));
                    }

                    if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                        options.add(
                                Arrays.asList(
                                        HudHandler.option(valid,
                                                String.format("[#B0B0B0]%s", server.getDescription()))));
                    }

                });

                options.add(Arrays.asList(//
                        page > 0//
                                ? HudHandler.option((p, state) -> {
                                    sendRedirectServerList(player, (int) state - 1);
                                    HudHandler.closeFollowDisplay(p, HudHandler.SERVERS_UI);
                                }, "[yellow]Previous")
                                : HudHandler.option(invalid, "First page"), //
                        servers.size() == size//
                                ? HudHandler.option((p, state) -> {
                                    sendRedirectServerList(player, (int) state + 1);
                                    HudHandler.closeFollowDisplay(p, HudHandler.SERVERS_UI);
                                }, "[green]Next")
                                : HudHandler.option(invalid, "No more")));

                options.add(Arrays.asList(HudHandler.option(
                        (p, state) -> HudHandler.closeFollowDisplay(p, HudHandler.SERVERS_UI),
                        "[red]Close")));

                HudHandler.showFollowDisplays(player, HudHandler.SERVERS_UI, "Servers", "",
                        Integer.valueOf(page), options);
            } catch (Exception e) {
                Log.err(e);
            }
        });
    }

}
