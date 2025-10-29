package plugin.controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import arc.Core;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.CommandHandler.Command;
import arc.util.CommandHandler.ResponseType;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;
import plugin.Config;
import plugin.ServerController;
import plugin.handler.EventHandler;
import plugin.handler.HudHandler;
import plugin.handler.ServerCommandHandler;
import plugin.handler.SessionHandler;
import plugin.handler.VoteHandler;
import dto.PlayerInfoDto;
import dto.CommandParamDto;
import dto.MindustryPlayerDto;
import dto.PlayerDto;
import dto.ServerCommandDto;
import dto.StartServerDto;
import dto.ServerStateDto;
import dto.TeamDto;
import plugin.utils.Utils;
import io.javalin.Javalin;
import io.javalin.http.ContentType;

public class GeneralController {

    public static void init(Javalin app) {
        app.get("state", ctx -> {
            ServerStateDto state = Utils.appPostWithTimeout(Utils::getState);
            ctx.contentType(ContentType.APPLICATION_JSON);
            ctx.json(state);
        });

        app.get("image", ctx -> {
            byte[] mapPreview = Utils.appPostWithTimeout(Utils::mapPreview);
            ctx.contentType(ContentType.IMAGE_PNG).result(mapPreview);
        });

        app.get("plugin-version", ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON);
            ctx.json(Config.PLUGIN_VERSION);
        });

        app.get("hosting", (ctx) -> {
            ctx.contentType(ContentType.APPLICATION_JSON);
            ctx.json(Vars.state.isGame());
        });

        app.post("discord", ctx -> {
            String message = ctx.body();

            Call.sendMessage(message);
            ctx.result();
        });

        app.post("pause", ctx -> {
            if (Vars.state.isPaused()) {
                Vars.state.set(State.playing);
            } else if (Vars.state.isPlaying()) {
                Vars.state.set(State.paused);
            }
            ctx.json(Vars.state.isPaused());
        });

        app.post("host", ctx -> {
            StartServerDto request = ctx.bodyAsClass(StartServerDto.class);
            host(request);
            ctx.result();
        });

        app.post("set-player", ctx -> {
            MindustryPlayerDto request = ctx.bodyAsClass(MindustryPlayerDto.class);

            String uuid = request.getUuid();
            boolean isAdmin = request.isAdmin();

            PlayerInfo target = Vars.netServer.admins.getInfoOptional(uuid);
            Player player = Groups.player.find(p -> p.getInfo() == target);

            if (target != null) {
                if (isAdmin) {
                    Vars.netServer.admins.adminPlayer(target.id,
                            player == null ? target.adminUsid : player.usid());
                } else {
                    Vars.netServer.admins.unAdminPlayer(target.id);
                }
                if (player != null)
                    player.admin = isAdmin;
            } else {
                Log.err("Nobody with that name or ID could be found. If adding an admin by name, make sure they're online; otherwise, use their UUID.");
            }

            if (player != null) {
                HudHandler.closeFollowDisplay(player, HudHandler.LOGIN_UI);
                EventHandler.setPlayerData(request, player);
            }
            ctx.result();

        });

        app.get("players", ctx -> {

            ArrayList<Player> players = new ArrayList<Player>();
            Groups.player.forEach(players::add);

            List<PlayerDto> result = Utils
                    .appPostWithTimeout(() -> (players.stream()//
                            .map(player -> new PlayerDto()//
                                    .setName(player.coloredName())//
                                    .setUuid(player.uuid())//
                                    .setIp(player.ip())
                                    .setLocale(player.locale())//
                                    .setIsAdmin(player.admin)//
                                    .setJoinedAt(SessionHandler.contains(player) //
                                            ? SessionHandler.get(player).joinedAt
                                            : Instant.now().toEpochMilli())
                                    .setTeam(new TeamDto()//
                                            .setColor(player.team().color.toString())//
                                            .setName(player.team().name)))
                            .collect(Collectors.toList())));

            ctx.json(result);
        });

        app.get("player-infos", ctx -> {
            String pageString = ctx.queryParam("page");
            String sizeString = ctx.queryParam("size");
            String isBannedString = ctx.queryParam("banned");
            String filter = ctx.queryParam("filter");

            int page = pageString != null ? Integer.parseInt(pageString) : 0;
            int size = sizeString != null ? Integer.parseInt(sizeString) : 10;
            Boolean isBanned = isBannedString != null ? Boolean.parseBoolean(isBannedString) : null;

            int offset = page * size;

            List<Predicate<PlayerInfo>> conditions = new ArrayList<>();

            if (filter != null) {
                conditions.add(info -> //
                info.names.contains(name -> name.contains(filter))
                        || info.ips.contains(ip -> ip.contains(filter)));
            }

            if (isBanned != null) {
                conditions.add(info -> info.banned == isBanned);
            }

            List<PlayerInfoDto> result = Utils.appPostWithTimeout(() -> {
                Seq<PlayerInfo> bans = Vars.netServer.admins.playerInfo.values().toSeq();

                return bans.list()//
                        .stream()//
                        .filter(info -> conditions.stream().allMatch(condition -> condition.test(info)))//
                        .skip(offset)//
                        .limit(size)//
                        .map(ban -> new PlayerInfoDto()
                                .setId(ban.id)
                                .setLastName(ban.lastName)
                                .setLastIP(ban.lastIP)
                                .setIps(ban.ips.list())
                                .setNames(ban.names.list())
                                .setAdminUsid(ban.adminUsid)
                                .setTimesKicked(ban.timesKicked)
                                .setTimesJoined(ban.timesJoined)
                                .setBanned(ban.banned)
                                .setAdmin(ban.admin)
                                .setLastKicked(ban.lastKicked))
                        .collect(Collectors.toList());
            });

            ctx.json(result);
        });

        app.get("kicks", ctx -> {

            HashMap<Object, Object> result = Utils.appPostWithTimeout(() -> {
                HashMap<Object, Object> res = new HashMap<>();
                for (ObjectMap.Entry<String, Long> entry : Vars.netServer.admins.kickedIPs.entries()) {
                    if (entry.value != 0 && Time.millis() - entry.value < 0) {
                        res.put(entry.key, entry.value);
                    }
                }
                return res;
            });

            ctx.json(result);
        });

        app.get("commands", ctx -> {
            List<ServerCommandDto> commands = ServerCommandHandler.getHandler() == null
                    ? Arrays.asList()
                    : ServerCommandHandler.getHandler()//
                            .getCommandList()
                            .map(command -> new ServerCommandDto()
                                    .setText(command.text)
                                    .setDescription(command.description)
                                    .setParamText(command.paramText)
                                    .setParams(new Seq<>(command.params)
                                            .map(param -> new CommandParamDto()//
                                                    .setName(param.name)//
                                                    .setOptional(param.optional)
                                                    .setVariadic(param.variadic))//
                                            .list()))
                            .list();

            ctx.json(commands);
        });

        app.post("commands", ctx -> {
            String[] commands = ctx.bodyAsClass(String[].class);

            if (commands != null) {
                for (String command : commands) {
                    Log.info("Execute command: " + command);

                    ServerCommandHandler.execute(command, response -> {
                        if (response.type == ResponseType.unknownCommand) {

                            int minDst = 0;
                            Command closest = null;

                            for (Command cmd : ServerCommandHandler.getHandler().getCommandList()) {
                                int dst = Strings.levenshtein(cmd.text, response.runCommand);

                                if (dst < 3 && (closest == null || dst < minDst)) {
                                    minDst = dst;
                                    closest = cmd;
                                }
                            }

                            if (closest != null && !closest.text.equals("yes")) {
                                Log.err("Command not found. Did you mean \"" + closest.text + "\"?");
                            } else {
                                Log.err("Invalid command. Type 'help' for help.");
                            }
                        } else if (response.type == ResponseType.fewArguments) {
                            Log.err("Too few command arguments. Usage: " + response.command.text + " "
                                    + response.command.paramText);
                        } else if (response.type == ResponseType.manyArguments) {
                            Log.err("Too many command arguments. Usage: " + response.command.text + " "
                                    + response.command.paramText);
                        }
                    });
                }
            }
            ctx.result();
        });

        app.post("say", ctx -> {
            if (!Vars.state.isGame()) {
                Log.err("Not hosting. Host a game first.");
            } else {
                String message = ctx.body();
                Call.sendMessage("[]" + message);
            }

            ctx.result();
        });

        app.get("json", ctx -> {
            HashMap<String, Object> res = Utils.appPostWithTimeout(() -> {

                HashMap<String, Object> data = new HashMap<>();

                data.put("state", Utils.getState());
                data.put("session", SessionHandler.get());
                data.put("hud", HudHandler.menus.asMap());
                data.put("isHub", Config.IS_HUB);
                data.put("ip", Config.SERVER_IP);
                data.put("units", Groups.unit.size());
                data.put("enemies", Vars.state.enemies);
                data.put("tps", Core.graphics.getFramesPerSecond());

                HashMap<String, Object> gameStats = new HashMap<>();

                gameStats.put("buildingsBuilt", Vars.state.stats.buildingsBuilt);
                gameStats.put("buildingsDeconstructed", Vars.state.stats.buildingsDeconstructed);
                gameStats.put("buildingsDestroyed", Vars.state.stats.buildingsDestroyed);
                gameStats.put("coreItemCount", Vars.state.stats.coreItemCount);
                gameStats.put("enemyUnitsDestroyed", Vars.state.stats.enemyUnitsDestroyed);
                gameStats.put("placedBlockCount", Vars.state.stats.placedBlockCount);
                gameStats.put("unitsCreated", Vars.state.stats.unitsCreated);
                gameStats.put("wavesLasted", Vars.state.stats.wavesLasted);

                HashMap<String, String> executors = new HashMap<>();
                executors.put("backgroundExecutor", ServerController.BACKGROUND_TASK_EXECUTOR.toString());
                executors.put("backgroundScheduler", ServerController.BACKGROUND_SCHEDULER.toString());

                data.put("executors", executors);

                data.put("gameStats", gameStats);
                data.put("locales", Vars.locales);
                data.put("threads",
                        Thread.getAllStackTraces().keySet().stream()
                                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                                .map(thread -> {
                                    HashMap<String, Object> info = new HashMap<>();

                                    info.put("id", thread.getId());
                                    info.put("name", thread.getName());
                                    info.put("state", thread.getState().name());
                                    info.put("group", thread.getThreadGroup() == null ? "null"
                                            : thread.getThreadGroup().getName());
                                    info.put("stacktrace", Arrays.asList(thread.getStackTrace()).stream()
                                            .map(stack -> stack.toString()).collect(Collectors.toList()));

                                    return info;
                                })
                                .collect(Collectors.toList()));

                ArrayList<HashMap<String, String>> maps = new ArrayList<HashMap<String, String>>();
                Vars.maps.all().forEach(map -> {
                    HashMap<String, String> tags = new HashMap<>();
                    map.tags.each((key, value) -> tags.put(key, value));
                    maps.add(tags);
                });
                data.put("maps",
                        Vars.maps.all().map(map -> {
                            HashMap<String, Object> info = new HashMap<>();
                            info.put("name", map.name()); //
                            info.put("author", map.author()); //
                            info.put("file", map.file.absolutePath());
                            info.put("tags", map.tags);
                            info.put("description", map.description());
                            info.put("width", map.width);
                            info.put("height", map.height);

                            return info;
                        }).list());
                data.put("mods", Vars.mods.list().map(mod -> mod.meta.toString()).list());
                data.put("votes", VoteHandler.votes);

                HashMap<String, Object> settings = new HashMap<String, Object>();

                Core.settings.keys().forEach(key -> {
                    settings.put(key, Core.settings.get(key, null));
                });

                data.put("settings", settings);

                return data;
            });

            ctx.json(res);
        });
    }

    private static synchronized void host(StartServerDto request) {
        if (Vars.state.isGame()) {
            Log.info("Already hosting. Type 'stop' to stop hosting first.");
            return;
        }

        String mapName = request.getMapName();
        String gameMode = request.getMode();
        String commands = request.getHostCommand();

        if (commands != null && !commands.trim().isEmpty()) {
            String[] commandsArray = commands.split("\n");
            for (String command : commandsArray) {
                Log.info("Host command: " + command);
                ServerCommandHandler.execute(command, (_ignore) -> {
                });
            }
            return;
        }

        Utils.host(mapName, gameMode);
    }
}
