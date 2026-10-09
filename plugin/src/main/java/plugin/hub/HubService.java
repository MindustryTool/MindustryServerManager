package plugin.hub;

import plugin.session.SessionService;
import plugin.gateway.ApiGateway;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.graphics.Color;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import common.server.Server;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.core.Version;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.MapObjectives;
import mindustry.game.MapObjectives.FlagObjective;
import mindustry.game.MapObjectives.TextMarker;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.WorldLabel;
import mindustry.net.ArcNetProvider;
import mindustry.net.Administration;
import mindustry.net.Net;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.core.Scheduler;
import plugin.Control;
import plugin.Tasks;

@Component
@RequiredArgsConstructor
@ConditionOn(Cfg.OnHub.class)
public class HubService {
    private final Seq<ServerCore> serverCores = new Seq<>();
    private Seq<Server> servers = new Seq<>();

    private final SessionService sessionService;
    private final ApiGateway apiGateway;
    private final Scheduler scheduler;
    private final PlayerConnectService playerConnectService;

    private final HashMap<Player, CompletableFuture<Boolean>> futureMap = new HashMap<>();

    private enum LabelType {
        WorldLabel,
        WorldProcessor,
        MapObjective
    }

    private LabelType type = LabelType.WorldLabel;

    @Init
    public void init() {
        for (var block : Vars.content.blocks()) {
            Vars.state.rules.bannedBlocks.add(block);
        }

        for (var unit : Vars.content.units()) {
            Vars.state.rules.bannedUnits.add(unit);
        }

        setupCustomServerDiscovery();
        loadCores();
        refreshServerList();
        setupCustomPacketHandler();
    }

    private void setupCustomPacketHandler() {
        Vars.netServer.addPacketHandler("has-player-connect", (player, result) -> {
            CompletableFuture<Boolean> future = futureMap.get(player);

            if (future == null) {
                return;
            }

            if (future.isDone()) {
                futureMap.remove(player);
                return;
            }

            future.complete(true);
            futureMap.remove(player);
        });
    }

    public CompletableFuture<Boolean> hasPlayerConnect(Player player) {
        CompletableFuture<Boolean> result = futureMap.computeIfAbsent(player, key -> new CompletableFuture<>());
        
        scheduler.schedule(() -> {
            result.complete(false);
            futureMap.remove(player);
        }, 3, TimeUnit.SECONDS);

        Call.clientPacketReliable(player.con, "has-player-connect", "true");

        return result;
    }

    public void sendConnectPlayerConnect(Player player, String roomLink) {
        Call.clientPacketReliable(player.con, "connect-player-connect", roomLink);
    }

    @Listener(WorldLoadEvent.class)
    private void loadCores() {
        Tasks.io("Refresh server list", () -> {
            serverCores.clear();

            float centerX = Vars.world.unitWidth() / 2;
            float centerY = Vars.world.unitHeight() / 2;

            var cores = Team.sharded.cores().sort((a, b) -> Float.compare(
                    (a.getX() - centerX) * (a.getX() - centerX) + (a.getY() - centerY) * (a.getY() - centerY)
                            - a.hitSize(),
                    (b.getX() - centerX) * (b.getX() - centerX) + (b.getY() - centerY) * (b.getY() - centerY)
                            - b.hitSize()));

            for (var core : cores) {
                serverCores.add(new ServerCore(null, core.getX(), core.getY(), core.hitSize()));
            }
        });
    }

    @Listener
    private void onPlayerJoin(PlayerJoin event) {
        refreshServerList();
        renderServerLabels();
    }

    private static class DiscoverySnapshot {
        final String name;
        final String description;
        final String map;
        final int totalPlayers;
        final long timestamp;

        DiscoverySnapshot(String name, String description, String map, int totalPlayers) {
            this.name = name;
            this.description = description;
            this.map = map;
            this.totalPlayers = totalPlayers;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 30_000;
        }
    }

    private DiscoverySnapshot discoverySnapshot;

    private synchronized DiscoverySnapshot getDiscoverySnapshot() {
        if (discoverySnapshot != null && !discoverySnapshot.isExpired()) {
            return discoverySnapshot;
        }

        String name = Administration.Config.serverName.string();
        String description = Administration.Config.desc.string();
        String map = Vars.state.map != null ? Vars.state.map.name() : "";
        int players = Groups.player.size();

        try {
            var fetchedServers = Seq.with(apiGateway.getServers(new PaginationRequest().setPage(0).setSize(20)));
            int dedicatedServerPlayers = 0;

            if (fetchedServers.size > 0) {
                var serverData = fetchedServers
                        .select(s -> s.getPlayers() > 0)
                        .random();

                dedicatedServerPlayers = fetchedServers.sum(s -> (int) s.getPlayers());

                if (serverData != null) {
                    name = serverData.getName() + " [lime][HUB]";
                    description = serverData.getDescription();
                    map = serverData.getMapName() == null ? "" : serverData.getMapName();
                }
            }

            int playerConnectPlayers = playerConnectService != null ? playerConnectService.getTotalPlayerCount() : 0;
            int totalPlayers = dedicatedServerPlayers + playerConnectPlayers;
            if (totalPlayers > 0) {
                players = totalPlayers;
            }
        } catch (Exception e) {
            Log.err("Failed to refresh discovery snapshot: " + e.getMessage());
        }

        discoverySnapshot = new DiscoverySnapshot(name, description, map, players);
        return discoverySnapshot;
    }

    private void setupCustomServerDiscovery() {
        try {
            var providerField = Net.class.getDeclaredField("provider");
            providerField.setAccessible(true);
            var provider = (ArcNetProvider) providerField.get(Vars.net);
            var serverField = ArcNetProvider.class.getDeclaredField("server");
            serverField.setAccessible(true);
            var server = (arc.net.Server) serverField.get(provider);

            server.setDiscoveryHandler((address, handler) -> {
                DiscoverySnapshot snapshot = getDiscoverySnapshot();

                ByteBuffer buffer = ByteBuffer.allocate(500);

                writeString(buffer, snapshot.name, 100);
                writeString(buffer, snapshot.map, 64);

                buffer.putInt(Core.settings.getInt("totalPlayers", snapshot.totalPlayers));
                buffer.putInt(Vars.state.wave);
                buffer.putInt(Version.build);
                writeString(buffer, Version.type);

                buffer.put((byte) Vars.state.rules.mode().ordinal());
                buffer.putInt(Vars.netServer.admins.getPlayerLimit());

                writeString(buffer, snapshot.description, 100);
                if (Vars.state.rules.modeName != null) {
                    writeString(buffer, Vars.state.rules.modeName, 50);
                }
                buffer.position(0);
                handler.respond(buffer);

                buffer.clear();
            });

        } catch (Exception e) {
            Log.err(e);
        }
    }

    private void writeString(ByteBuffer buffer, String string) {
        writeString(buffer, string, 32);
    }

    private void writeString(ByteBuffer buffer, String string, int maxlen) {
        byte[] bytes = string.getBytes(Vars.charset);
        if (bytes.length > maxlen) {
            bytes = Arrays.copyOfRange(bytes, 0, maxlen);
        }

        buffer.put((byte) bytes.length);
        buffer.put(bytes);
    }

    @Listener
    private void onTap(TapEvent event) {
        if (event.tile == null) {
            return;
        }

        var map = Vars.state.map;

        if (map == null) {
            return;
        }

        var tapX = event.tile.worldx();
        var tapY = event.tile.worldy();

        Call.effectReliable(Fx.coreBuildShockwave, tapX, tapY, 0, Color.white);

        for (var core : serverCores) {
            var tapSize = core.getSize();

            if (tapX >= core.getX() - tapSize
                    && tapX <= core.getX() + tapSize
                    && tapY >= core.getY() - tapSize
                    && tapY <= core.getY() + tapSize) {
                if (core.getEntry() == null) {
                    continue;
                }

                core.getEntry().onInteract(event.player, sessionService, this);
                break;
            }
        }
    }

    @Schedule(fixedDelay = 5, unit = TimeUnit.SECONDS)
    private void refreshServerList() {
        if (Groups.player.size() <= 0) {
            return;
        }

        try {
            var request = new PaginationRequest()
                    .setPage(0)
                    .setSize(serverCores.size + 5);

            servers = Seq.with(apiGateway.getServers(request))
                    .select(server -> !server.getId().equals(Control.SERVER_ID));

            List<HubEntry> entries = new ArrayList<>();
            for (var server : servers) {
                entries.add(new ServerHubEntry(server));
            }

            if (playerConnectService != null) {
                for (var room : playerConnectService.getActiveRooms()) {
                    entries.add(new PlayerConnectHubEntry(room));
                }
            }

            entries.sort((a, b) -> Integer.compare(b.getPlayers(), a.getPlayers()));

            for (int i = 0; i < serverCores.size; i++) {
                var core = serverCores.get(i);

                if (i < entries.size()) {
                    var data = entries.get(i);
                    core.setEntry(data);
                } else {
                    core.setEntry(null);
                }
            }

        } catch (Exception e) {
            Log.err("Failed to refresh server list: " + e.getMessage());
        }
    }

    @Schedule(delay = 5, fixedDelay = 5, unit = TimeUnit.SECONDS)
    private void renderServerLabels() {
        if (Groups.player.size() <= 0) {
            return;
        }

        var map = Vars.state.map;

        if (map == null) {
            return;
        }

        switch (type) {
            case WorldLabel: {
                int flags = WorldLabel.flagBackground | WorldLabel.flagOutline;
                for (int i = 0; i < serverCores.size; i++) {
                    var core = serverCores.get(i);
                    HubEntry entry = core.getEntry();
                    if (entry == null) {
                        continue;
                    }

                    String message = entry.renderLabel();
                    Call.label(message, i + 1, 5.1f, core.getX(), core.getY(), flags);
                }
                break;
            }

            case WorldProcessor: {
                var tile = Vars.world.tile(0, 0);

                if (tile == null) {
                    return;
                }

                if (tile.build == null || !(tile.build instanceof LogicBuild)) {
                    Call.setTile(tile, Blocks.worldProcessor, Team.sharded, 0);
                } else {
                    if (tile.build instanceof LogicBuild logic) {
                        logic.updateCode("");
                    }
                }

                break;
            }

            case MapObjective: {
                MapObjectives objectives = new MapObjectives();
                FlagObjective flagObjective = new FlagObjective();

                Seq<TextMarker> markers = new Seq<>();

                for (var core : serverCores) {
                    var marker = createServerMarker(core);
                    if (marker != null) {
                        markers.add(marker);
                    }
                }

                if (markers.isEmpty()) {
                    return;
                }

                TextMarker[] markerArray = new TextMarker[markers.size];
                for (int i = 0; i < markers.size; i++) {
                    markerArray[i] = markers.get(i);
                }

                flagObjective.markers(markerArray);
                objectives.add(flagObjective);

                if (objectives.any()) {
                    Call.setObjectives(objectives);
                }
                break;
            }

            default:
                break;
        }
    }

    private TextMarker createServerMarker(ServerCore core) {
        HubEntry entry = core.getEntry();

        if (entry == null) {
            return null;
        }

        float x = core.getX();
        float y = core.getY();

        String message = entry.renderLabel();

        return new TextMarker(message, x, y);
    }

    public static String newLine(String text) {
        if (text == null) {
            return "";
        }

        String[] word = text.split(" ");

        StringBuilder sb = new StringBuilder();

        int currentLength = 0;

        for (int i = 0; i < word.length; i++) {
            sb.append(word[i]);

            if (currentLength > 20) {
                sb.append("\n");
                currentLength = 0;
            } else {
                sb.append(" ");
                currentLength += Strings.stripColors(word[i]).length();
            }
        }

        return sb.toString();
    }
}
