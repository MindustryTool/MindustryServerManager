package plugin.service;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.neovisionaries.ws.client.WebSocket;

import arc.struct.Seq;
import arc.util.Http;
import arc.util.Http.HttpStatusException;
import arc.util.Log;
import plugin.Control;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Listener;
import plugin.core.Scheduler;
import plugin.event.SessionCreatedEvent;
import plugin.event.SessionRemovedEvent;
import plugin.utils.HttpUtils;
import plugin.utils.JsonUtils;
import plugin.utils.Utils;
import plugin.type.PaginationRequest;
import dto.LoginDto;
import dto.LoginRequestDto;
import dto.MessageHandler;
import dto.ServerDto;
import dto.ServerStateDto;
import dto.WsMessage;
import events.BaseEvent;
import events.ServerEvents.ServerStateEvent;
import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.StateChangeEvent;
import mindustry.game.EventType.WorldLoadEndEvent;
import mindustry.gen.Player;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
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
import dto.CommandParamDto;
import dto.PlayerInfoDto;
import dto.ServerCommandDto;
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
    private static final String GATEWAY_URL = "http://server.mindustry-tool.com:8089/gateway";
    private static final Executor executor = Executors.newCachedThreadPool();

    private final Scheduler scheduler;

    private final Duration HEARTBEAT_DURATION = Duration.ofSeconds(10);
    private final HashMap<String, MessageHandler<Object, Object>> messageHandlers = new HashMap<>();
    private final WsHandler wsHandler = new WsHandler();

    private Instant lastSendEventAt = Instant.now();
    private WebSocket webSocket;

    private static boolean disableTranslation = true;

    private Cache<PaginationRequest, List<ServerDto>> serverQueryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(10)
            .build();

    private final Cache<String, String> translationCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    private final Cache<String, Boolean> failedTranslationCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    private final Map<UUID, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    private boolean isDestroyed = false;

    @Init
    public void init() {
        connect();

        this.registerMessageHandler("get-json", Void.class, (request) -> getJson());
        this.registerMessageHandler("get-plugin-version", Void.class, (request) -> Cfg.PLUGIN_VERSION);
        this.registerMessageHandler("update-player", LoginDto.class, this::updatePlayer);
        this.registerMessageHandler("paused", Void.class, (request) -> tooglePause());
        this.registerMessageHandler("get-state", Void.class, (request) -> Utils.getState());
        this.registerMessageHandler("generate-map-image", Void.class, (request) -> generateMapImage());
        this.registerMessageHandler("send-command", String[].class, (request) -> sendCommand(request));
        this.registerMessageHandler("say", String.class, (request) -> say(request));
        this.registerMessageHandler("host", StartServerDto.class, (request) -> host(request));
        this.registerMessageHandler("chat", String.class, (request) -> sendChat(request));
        this.registerMessageHandler("is-hosting", Void.class, (request) -> isHosting());
        this.registerMessageHandler("get-commands", Void.class, (request) -> getCommands());
        this.registerMessageHandler("get-players-info", JsonNode.class, (request) -> getPlayersInfo(request));
        this.registerMessageHandler("get-kicked-ips", Void.class, (request) -> getKicks());
    }

    public <R> CompletableFuture<R> sendRequest(String type, Object payload, Class<R> clazz) {
        WsMessage<?> request = WsMessage.create(type).withPayload(payload);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(request.getId(), future);

        future.whenComplete((_res, _err) -> pendingRequests.remove(request.getId()));

        try {
            send(request);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future.thenApply(r -> JsonUtils.readJsonAsClass(r, clazz));
    }

    public void fire(BaseEvent event) {
        sendRequest("event", event);
    }

    public CompletableFuture<Void> sendRequest(String type, Object payload) {
        return sendRequest(type, payload, Void.class);
    }

    private synchronized void connect() {
        try {
            if (webSocket != null && webSocket.isOpen()) {
                return;
            }

            Log.info("[sky]Connecting to server manager");
            webSocket = new WebSocketFactory()
                    .createSocket(GATEWAY_URL)
                    .addHeader("Authorization", Cfg.webSocketAuthToken())
                    .addHeader("X-SERVER-ID", Cfg.serverId())
                    .addListener(wsHandler)
                    .connectAsynchronously();
        } catch (Exception e) {
            Log.err("Error connecting to server manager", e);
            reconnect();
        }
    }

    private class WsHandler extends WebSocketAdapter {
        @Override
        public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {
            Log.info("[green]Connected to server manager");
        }

        @Override
        public void onTextMessage(WebSocket ws, String message) {
            executor.execute(() -> {
                try {
                    handleMessage(ws, message);
                } catch (Exception e) {
                    Log.err("Error processing message", e);
                }
            });
        }

        @Override
        public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame,
                WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
            if (isDestroyed) {
                return;
            }

            if (closedByServer) {
                Log.info("[red]Server manager disconnected: " + serverCloseFrame);
            } else {
                Log.info("[red]Client disconnected: " + clientCloseFrame);
            }
            reconnect();
        }

        @Override
        public void onConnectError(WebSocket websocket, WebSocketException exception) throws Exception {
            if (isDestroyed) {
                return;
            }
            Log.err("Error connecting to server manager", exception);
            reconnect();
        }
    }

    @Schedule(delay = 5, fixedDelay = 10, unit = TimeUnit.SECONDS)
    private void keepAlive() {
        if (lastSendEventAt.plus(HEARTBEAT_DURATION).isBefore(Instant.now()) && isConnected()) {
            sendStateUpdate();
        }
    }

    private void handleMessage(WebSocket ws, String message) {
        JsonNode json = JsonUtils.readJson(message);
        JsonNode payload = json.get("payload");
        WsMessage<?> wsMessage = JsonUtils.readJsonAsClass(message, WsMessage.class);

        if (wsMessage.getResponseOf() != null) {
            CompletableFuture<JsonNode> future = pendingRequests.remove(wsMessage.getResponseOf());
            if (future == null) {
                Log.warn("No future found for responseOf: @", wsMessage.getResponseOf());
                return;
            }
            if (wsMessage.isError()) {
                future.completeExceptionally(new RuntimeException(payload.toString()));
            } else {
                future.complete(payload);
            }
            return;
        }

        MessageHandler<Object, Object> handler = messageHandlers.get(wsMessage.getType());

        if (handler != null) {
            try {
                Object param = JsonUtils.readJsonAsClass(payload, handler.getClazz());
                Object result = handler.getFn().apply(param);
                WsMessage<?> response = wsMessage.response(result);
                ws.sendText(JsonUtils.toJsonString(response));
            } catch (Exception e) {
                Log.err("Error handling message: " + wsMessage, e);
                WsMessage<?> error = wsMessage.error(e.getMessage());
                ws.sendText(JsonUtils.toJsonString(error));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <Req, Res> void registerMessageHandler(String type, Class<Req> clazz, Function<Req, Res> handler) {
        MessageHandler<Object, Object> mh = new MessageHandler<Object, Object>((Class<Object>) clazz,
                (Function<Object, Object>) handler);
        messageHandlers.put(type, mh);
    }

    private synchronized void reconnect() {
        Log.info("[yellow]Reconnecting to server manager in 10 seconds");
        scheduler.schedule(this::connect, 10, TimeUnit.SECONDS);
    }

    private boolean isConnected() {
        return webSocket != null && webSocket.isOpen();
    }

    public void send(WsMessage<?> event) {
        if (isConnected()) {
            webSocket.sendText(JsonUtils.toJsonString(event));
            lastSendEventAt = Instant.now();
        } else {
            Log.warn("Not connected, dropped event: @", event);
        }
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
            data.put("session", Registry.get(SessionHandler.class).get());
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
            data.put("votes", Registry.get(VoteService.class).votes);

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
        Boolean isAdmin = request.getIsAdmin();
        String uuid = request.getUuid();

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
            Registry.get(SessionHandler.class).getByUuid(uuid).ifPresent(
                    session -> Registry.get(SessionService.class).setLogin(session, request));
        }

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

    private static synchronized Void host(StartServerDto request) {
        if (Vars.state.isGame()) {
            Log.warn("API: Already hosting. Type 'stop' to stop hosting first.");
            return null;
        }

        String mapName = request.getMapName();
        String gameMode = request.getMode();
        String commands = request.getHostCommand();

        if (commands != null && !commands.trim().isEmpty()) {
            String[] commandsArray = commands.split("\n");
            for (String command : commandsArray) {
                Log.info("[sky]Host command: " + command);
                Registry.get(ServerCommandHandler.class).execute(command, (_ignore) -> {
                });
            }
            return null;
        }

        Utils.host(mapName, gameMode);
        return null;
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
            Call.sendMessage("[scarlet][Server][white] " + message);
        }

        return null;
    }

    private List<PlayerInfoDto> getPlayersInfo(JsonNode node) {
        String pageString = node.get("page").asText();
        String sizeString = node.get("size").asText();
        String isBannedString = node.path("banned").asText(null);
        String filter = node.path("filter").asText(null);

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
        }, "Get player info");

        return result;
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

    public String host(String targetServerId) {
        Object lock = Utils.getHostingLock(targetServerId);

        synchronized (lock) {
            Log.info("[sky]Hosting server: " + targetServerId);
            try {
                return sendRequest("host", targetServerId, String.class).get(90, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException("Host failed", e);
            } finally {
                Utils.releaseHostingLock(targetServerId);
                Log.info("[sky]Finish hosting server: " + targetServerId);
            }
        }

    }

    public synchronized List<ServerDto> getServers(PaginationRequest request) {
        return serverQueryCache.get(request, _ignore -> {
            try {
                String query = String.format("servers?page=%s&size=%s", request.getPage(), request.getSize());

                return HttpUtils.sendList(HttpUtils.get(API_URL, query), Duration.ofSeconds(5), ServerDto.class);
            } catch (Exception e) {
                e.printStackTrace();
                return new ArrayList<>();
            }
        });
    }

    public String translateRaw(Locale targetLanguage, String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Text is empty");
        }

        var languageCode = targetLanguage.getLanguage();

        if (languageCode == null || languageCode.isEmpty()) {
            languageCode = "en";
        }

        HashMap<String, Object> body = new HashMap<>();

        body.put("content", text);
        body.put("target", languageCode);

        try {

            var result = HttpUtils
                    .send(HttpUtils
                            .post("https://api.mindustry-tool.com/api/v4/translations/translate")
                            .header("Content-Type", "application/json")//
                            .content(JsonUtils.toJsonString(body)), Duration.ofSeconds(20), String.class);

            return result;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Fail to translate: " + text + " to " + targetLanguage + ", error: " + e.getMessage());
        }
    }

    public String translate(String text, Locale targetLanguage) {
        if (disableTranslation) {
            return text;
        }

        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Text is empty");
        }

        var languageCode = targetLanguage.getLanguage();

        if (languageCode == null || languageCode.isEmpty()) {
            languageCode = "en";
        }

        var cacheKey = String.format("%s:%s", text, languageCode);

        if (failedTranslationCache.getIfPresent(cacheKey) != null) {
            return text;
        }

        var cached = translationCache.getIfPresent(cacheKey);

        if (cached != null) {
            return cached;
        }

        try {
            var result = translateRaw(targetLanguage, text);

            translationCache.put(cacheKey, result);

            Log.debug("Translate: <@> to <@>, result <@>", text, targetLanguage, result);

            return result;
        } catch (Exception e) {
            Log.err("Failed to translate text [" + text + "] to [" + targetLanguage + "]", e);
            failedTranslationCache.put(cacheKey, true);
            return text;
        }
    }

    public Seq<String> translate(Seq<String> texts, Locale targetLanguage) {
        if (disableTranslation) {
            return texts;
        }

        var languageCode = targetLanguage.getLanguage();

        if (languageCode == null || languageCode.isEmpty()) {
            languageCode = "en";
        }

        List<CompletableFuture<String>> result = new ArrayList<>();

        for (var text : texts.select(t -> !t.isEmpty())) {
            var future = new CompletableFuture<String>();

            result.add(future);

            var cacheKey = String.format("%s:%s", text, languageCode);

            var cached = translationCache.getIfPresent(cacheKey);

            if (cached != null) {
                future.complete(cached);
                continue;
            }

            if (failedTranslationCache.getIfPresent(cacheKey) != null) {
                future.complete(text);
                continue;
            }

            if (targetLanguage.equals(Locale.ENGLISH)) {
                future.complete(text);
                continue;
            }

            HashMap<String, Object> body = new HashMap<>();

            body.put("content", text);
            body.put("target", languageCode);

            Http.post("https://api.mindustry-tool.com/api/v4/translations/translate", JsonUtils.toJsonString(body))
                    .header("Content-Type", "application/json")//
                    .timeout(10000)
                    .error(error -> {
                        if (error instanceof SocketTimeoutException) {
                            future.completeExceptionally(
                                    new RuntimeException("Timeout while translating: " + text, error));
                        } else if (error instanceof HttpStatusException e) {
                            future.completeExceptionally(new RuntimeException(
                                    "Error while translating: " + text + "\n" + e.response.getResultAsString(), e));
                        } else {
                            future.completeExceptionally(
                                    new RuntimeException("Error while translating: " + text, error));
                        }
                        failedTranslationCache.put(cacheKey, true);
                    })
                    .submit(res -> {
                        try {
                            String translated = res.getResultAsString();

                            translationCache.put(cacheKey, translated);

                            Log.debug("Translate: <@> to <@>, result <@>", text, targetLanguage, translated);

                            future.complete(translated);
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                        }
                    });
        }

        try {
            CompletableFuture.allOf(result.toArray(new CompletableFuture[texts.size])).get(10, TimeUnit.SECONDS);

            return Seq.with(result).map(r -> r.getNow("This should never happen"));
        } catch (Exception e) {

            Log.err("Failed to translate text [" + texts + "] to [" + targetLanguage + "]", e);

            return texts;
        }
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

    @Listener(WorldLoadEndEvent.class)
    private void onWorldLoadEnd() {
        sendStateUpdate();
    }

    @Listener(PlayEvent.class)
    private void onPlay() {
        sendStateUpdate();
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

    @Destroy
    public void destroy() {
        isDestroyed = true;
        serverQueryCache.invalidateAll();

        if (webSocket != null) {
            webSocket.disconnect();
        }
    }
}
