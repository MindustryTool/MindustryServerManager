package plugin.gateway;

import plugin.vote.RtvService;

import plugin.session.SessionService;

import plugin.host.HostService;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dto.RecentPlayerDto;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gateway.client.WsClient;
import gateway.rpc.RequestContext;
import gateway.rpc.RpcChannel;

import arc.struct.Seq;
import arc.util.Log;
import plugin.Control;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.Listener;
import plugin.session.SessionCreatedEvent;
import plugin.session.SessionRemovedEvent;
import plugin.utils.HttpUtils;
import plugin.utils.JsonUtils;
import plugin.utils.Tr;
import plugin.utils.Utils;
import plugin.hub.PaginationRequest;
import dto.LoginDto;
import dto.LoginRequestDto;
import dto.ServerDto;
import dto.ServerStateDto;
import events.BaseEvent;
import events.ServerEvents.ServerStateEvent;
import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.StateChangeEvent;
import mindustry.gen.Player;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import arc.Core;
import arc.struct.ObjectMap;
import arc.util.CommandHandler.Command;
import arc.util.CommandHandler.ResponseType;
import arc.util.Strings;
import arc.util.Time;
import plugin.annotations.Init;
import plugin.annotations.Schedule;
import plugin.commands.ServerCommandHandler;
import plugin.Cfg;
import plugin.PluginState;
import plugin.core.Registry;
import plugin.event.UnloadServerEvent;
import dto.CommandParamDto;
import dto.PlayerInfoDto;
import dto.PlayerInfoPageDto;
import dto.ServerCommandDto;
import dto.ServerConfigDto;
import dto.StartServerDto;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.net.Administration.PlayerInfo;

@Component
@RequiredArgsConstructor
public class ApiGateway {

    private final String API_URL = "https://api.mindustry-tool.com/api/v4/";

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private final HostService hostService;
    private final SessionService sessionService;

    private final RpcChannel rpcChannel = RpcChannel.withMapper(JsonUtils.getObjectMapper(), executor);
    private final WsClient gatewayClient = WsClient.builder(URI.create(Cfg.gatewayUrl()), rpcChannel)
            .headersSupplier(() -> gatewayHeaders(Cfg.webSocketAuthToken(), Cfg.serverId()))
            .build();

    private Cache<PaginationRequest, List<ServerDto>> serverQueryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(10)
            .build();

    private boolean lastIsGame = true;

    static Map<String, String> gatewayHeaders(String jwt, String serverId) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (jwt != null && !jwt.isBlank()) {
            headers.put("Authorization", jwt);
        }
        if (serverId != null && !serverId.isBlank()) {
            headers.put("X-SERVER-ID", serverId);
        }
        return headers;
    }

    @Init
    public void init() {
        this.registerHandler("get-json", Void.class, _ctx -> getJson());
        this.registerHandler("update-player", LoginDto.class, ctx -> updatePlayer(ctx.body()));
        this.registerHandler("pause", Void.class, _ctx -> tooglePause());
        this.registerHandler("get-state", Void.class, _ctx -> Utils.getState());
        this.registerHandler("generate-map-image", Void.class, _ctx -> generateMapImage());
        this.registerHandler("send-command", String[].class, ctx -> sendCommand(ctx.body()));
        this.registerHandler("say", String.class, ctx -> say(ctx.body()));
        this.registerHandler("host", StartServerDto.class, ctx -> host(ctx.body()));
        this.registerHandler("chat", String.class, ctx -> sendChat(ctx.body()));
        this.registerHandler("is-hosting", Void.class, _ctx -> isHosting());
        this.registerHandler("get-commands", Void.class, _ctx -> getCommands());
        this.registerHandler("get-players-info", JsonNode.class, ctx -> getPlayersInfo(ctx.body()));
        this.registerHandler("get-kicked-ips", Void.class, _ctx -> getKicks());
        this.registerHandler("get-recent-players", Void.class, _ctx -> getRecentPlayers());
        this.registerHandler("delete-kicked-ip", String.class, ctx -> deleteKickedIp(ctx.body()));
        this.registerHandler("shutdown", Void.class, _ctx -> shutdown());

        gatewayClient.onOpen(() -> {
            Log.info("[green]Connected to server manager");
            sendStateUpdate();
        });
        gatewayClient.onClose(err -> Log.info("[red]Disconnected from server manager: " + err.getMessage()
                + "; reconnect scheduled"));
        gatewayClient.connect();
    }

    @Schedule(fixedDelay = 5, unit = TimeUnit.MINUTES)
    private void autoGenerateMapImage() {
        if (Vars.state.isPlaying()) {
            generateMapImage();
        }
    }

    @Schedule(fixedDelay = 10, unit = TimeUnit.SECONDS)
    private void autoHost() {
        try {
            boolean isGame = Vars.state.isGame();

            if (lastIsGame == false && isGame == false) {
                Log.info("[sky]Server not hosting, auto host");
                ServerConfigDto serverConfig = Cfg.serverConfig();
                if (serverConfig != null && serverConfig.getStartServer() != null) {
                    host(serverConfig.getStartServer());
                } else {
                    hostRemoteServer(Control.SERVER_ID.toString());
                }
            }

            lastIsGame = isGame;
        } catch (Exception e) {
            Log.err("Failed to host server", e);
        }
    }

    public <R> CompletableFuture<R> sendRequest(String type, Object payload, Class<R> clazz) {
        return rpcChannel.sendRequest(type, payload, clazz);
    }

    public void fire(BaseEvent event) {
        sendRequest("event", event);
    }

    public CompletableFuture<Void> sendRequest(String type, Object payload) {
        return rpcChannel.sendRequest(type, payload, Void.class);
    }

    /** Exposed for tests and adapters. */
    public RpcChannel rpcChannel() {
        return rpcChannel;
    }

    public void close() {
        WsClient client = gatewayClient;
        if (client != null) {
            client.close();
        }
    }

    public Void shutdown() {
        Log.info("[purple]Server shutdown");

        PluginEvents.fire(new UnloadServerEvent(false));
        return null;
    }

    public <Req, Res> void registerHandler(String type, Class<Req> clazz,
            Function<RequestContext<Req>, Res> handler) {
        rpcChannel.registerHandler(type, clazz, handler);
    }

    public boolean isConnected() {
        WsClient client = gatewayClient;
        return client != null && client.isOpen();
    }

    public static String toRelativeToServer(String path) {
        String config = "config";

        int index = path.indexOf(config);

        if (index == -1) {
            return path;
        }

        return path.substring(index + config.length());
    }

    private HashMap<String, Object> getJson() {
        HashMap<String, Object> res = Utils.appPostWithTimeout(() -> {

            HashMap<String, Object> data = new HashMap<>();

            data.put("state", Utils.getState());
            data.put("session", Registry.get(SessionService.class).get());
            data.put("isHub", Cfg.IS_HUB);
            data.put("ip", Cfg.SERVER_IP);
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
                        info.put("file", toRelativeToServer(map.file.absolutePath()));
                        info.put("tags", map.tags);
                        info.put("description", map.description());
                        info.put("width", map.width);
                        info.put("height", map.height);

                        return info;
                    }).list());
            data.put("mods", Vars.mods.list().map(mod -> mod.meta.toString()).list());
            data.put("votes", Registry.get(RtvService.class).votes);

            HashMap<String, Object> settings = new HashMap<String, Object>();

            Core.settings.keys().forEach(key -> {
                settings.put(key, Core.settings.get(key, null));
            });

            data.put("settings", settings);

            return data;
        }, "Get server info");

        return res;
    }

    private Void updatePlayer(LoginDto request) {
        String uuid = request.getUuid();
        SessionService sessionService = Registry.get(SessionService.class);
        Player player = Groups.player.find(p -> p.uuid().equals(uuid));

        if (player != null) {
            player.admin = false;
        }

        sessionService.getByUuid(uuid)
                .ifPresent(session -> sessionService.setLogin(session, request));

        return null;
    }

    private boolean tooglePause() {
        if (Vars.state.isPaused()) {
            Vars.state.set(State.playing);
        } else if (Vars.state.isPlaying()) {
            Vars.state.set(State.paused);
        }
        return Vars.state.isPaused();
    }

    private Void sendCommand(String[] commands) {
        if (commands != null) {
            Log.info("[sky]Execute commands: " + Arrays.toString(commands));
            for (String command : commands) {

                Registry.get(ServerCommandHandler.class).execute(command, response -> {
                    if (response.type == ResponseType.unknownCommand) {

                        int minDst = 0;
                        Command closest = null;

                        for (Command cmd : Registry.get(ServerCommandHandler.class).getHandler().getCommandList()) {
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

        return null;
    }

    private synchronized Void host(StartServerDto request) {
        String mapName = request.getMapName();
        String gameMode = request.getMode();
        String commands = request.getHostCommand();

        try {
            if (Vars.state.isGame()) {
                return null;
            }

            if (commands != null && !commands.trim().isEmpty()) {
                String[] commandsArray = commands.split("\n");

                for (String command : commandsArray) {
                    Log.info("[sky]Host command: " + command);
                    Registry.get(ServerCommandHandler.class).execute(command, (_ignore) -> {
                    });
                }
                return null;
            }

            hostService.host(mapName, gameMode);

            return null;
        } catch (Exception e) {
            Log.err("Failed to host server with map: " + mapName + " and mode: " + gameMode + " commands: " + commands,
                    e);
            throw new RuntimeException("Fail to host server", e);
        }
    }

    private List<ServerCommandDto> getCommands() {
        var handler = Registry.get(ServerCommandHandler.class);
        List<ServerCommandDto> commands = handler.getHandler() == null
                ? Arrays.asList()
                : handler.getHandler()//
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

        return commands;
    }

    private Void say(String message) {
        if (!Vars.state.isGame()) {
            Log.err("Not hosting. Host a game first.");
        } else {
            Utils.forEachPlayerLocale((locale, players) -> {
                String prefix = Tr.t(locale, "gateway.server_label");
                for (var p : players) {
                    p.sendMessage(prefix + message);
                }
            });
        }

        return null;
    }

    private PlayerInfoPageDto getPlayersInfo(JsonNode node) {
        String pageString = node.get("page").asText();
        String sizeString = node.get("size").asText();
        String filter = node.path("filter").asText(null);

        int page = pageString != null ? Integer.parseInt(pageString) : 0;
        int size = sizeString != null ? Integer.parseInt(sizeString) : 10;

        int offset = page * size;

        List<Predicate<PlayerInfo>> conditions = new ArrayList<>();

        if (filter != null) {
            conditions.add(info -> //
            info.names.contains(name -> name.contains(filter))
                    || info.ips.contains(ip -> ip.contains(filter))
                    || info.id.contains(filter));
        }

        if (node.has("banned")) {
            conditions.add(info -> info.banned == node.path("banned").asText().equals("true"));
        }

        return Utils.appPostWithTimeout(() -> {
            Seq<PlayerInfo> bans = Vars.netServer.admins.playerInfo.values().toSeq();

            List<PlayerInfo> filtered = bans.list()//
                    .stream()//
                    .filter(info -> conditions.stream().allMatch(condition -> condition.test(info)))//
                    .collect(Collectors.toList());

            List<PlayerInfoDto> data = filtered.stream()//
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

            return new PlayerInfoPageDto()
                    .setItems(filtered.size())
                    .setPage(page)
                    .setData(data);
        }, 2000, "Get player info");
    }

    private HashMap<Object, Object> getKicks() {
        HashMap<Object, Object> result = Utils.appPostWithTimeout(() -> {
            HashMap<Object, Object> res = new HashMap<>();
            for (ObjectMap.Entry<String, Long> entry : Vars.netServer.admins.kickedIPs.entries()) {
                if (entry.value != 0 && Time.millis() - entry.value < 0) {
                    res.put(entry.key, entry.value);
                }
            }
            return res;
        }, "Get kicks");

        return result;
    }

    private List<RecentPlayerDto> getRecentPlayers() {
        if (sessionService == null) {
            return Collections.emptyList();
        }
        return sessionService.getRecentPlayers();
    }

    private Boolean deleteKickedIp(String ip) {
        return Utils.appPostWithTimeout(() -> {
            if (ip == null || ip.isEmpty()) {
                return false;
            }
            Long removed = Vars.netServer.admins.kickedIPs.remove(ip);
            PlayerInfo info = Vars.netServer.admins.findByIP(ip);

            if (info != null) {
                info.lastKicked = 0;
            }

            return removed != null;
        }, "Delete kicked ip");
    }

    private Void generateMapImage() {
        Utils.generateMapPreview();
        return null;
    }

    private Void sendChat(String message) {
        Call.sendChatMessage(message);
        return null;
    }

    private Boolean isHosting() {
        return Vars.state.isGame() && Control.state == PluginState.LOADED;
    }

    public LoginDto login(Player player) {
        var body = new LoginRequestDto()
                .setUuid(player.uuid())
                .setName(player.name())
                .setIp(player.ip());

        try {
            return sendRequest("login", body, LoginDto.class).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Login failed", e);
        }
    }

    public synchronized String hostRemoteServer(String targetServerId) {
        try {
            Log.info("Hosting server: " + targetServerId);
            return sendRequest("host", targetServerId, String.class).get(90, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Host failed", e);
        } finally {
            Log.info("Finish hosting server: " + targetServerId);
        }
    }

    public synchronized List<ServerDto> getServers(PaginationRequest request) {
        return serverQueryCache.get(request, _ignore -> {
            try {
                String query = String.format("servers?page=%s&size=%s", request.getPage(), request.getSize());

                return HttpUtils.sendList(HttpUtils.get(API_URL, query), Duration.ofSeconds(5), ServerDto.class);
            } catch (Exception e) {
                Log.err("Failed to fetch server list: " + e.getMessage());
                return new ArrayList<>();
            }
        });
    }

    @Listener(SessionCreatedEvent.class)
    private void onSessionCreated() {
        sendStateUpdate();
    }

    @Listener(SessionRemovedEvent.class)
    private void onSessionRemoved() {
        sendStateUpdate();
    }

    @Listener(StateChangeEvent.class)
    private void onStateChange() {
        sendStateUpdate();
    }

    @Listener(PlayEvent.class)
    private void onWorldLoadEnd() {
        sendStateUpdate();
    }

    @Listener(PlayEvent.class)
    private void onPlay() {
        sendStateUpdate();
        generateMapImage();
    }

    private void sendStateUpdate() {
        try {
            ServerStateDto state = Utils.getState();
            ServerStateEvent event = new ServerStateEvent(Control.SERVER_ID, Arrays.asList(state));

            fire(event);
        } catch (Exception error) {
            Log.err("Failed to send state update", error);
        }
    }
}
