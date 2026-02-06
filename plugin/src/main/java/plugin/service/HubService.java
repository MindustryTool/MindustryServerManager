package plugin.service;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.graphics.Color;
import arc.net.Server;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import dto.ServerDto;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.core.Version;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net;
import plugin.Config;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.Control;
import plugin.Tasks;
import plugin.menus.ServerRedirectMenu;
import plugin.type.PaginationRequest;
import plugin.type.ServerCore;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
@ConditionOn(Config.OnHub.class)
public class HubService {

    private final Seq<ServerCore> serverCores = new Seq<>();
    private Seq<ServerDto> servers = new Seq<>();

    private final SessionHandler sessionHandler;
    private final ApiGateway apiGateway;

    @Init
    public void init() {
        if (!Config.IS_HUB) {
            return;
        }

        for (var block : Vars.content.blocks()) {
            Vars.state.rules.bannedBlocks.add(block);
        }

        for (var unit : Vars.content.units()) {
            Vars.state.rules.bannedUnits.add(unit);
        }

        setupCustomServerDiscovery();
        loadCores();
        refreshServerList();

        Control.SCHEDULER.scheduleWithFixedDelay(() -> {
            if (Groups.player.size() <= 0) {
                return;
            }
            refreshServerList();
        }, 0, 5, TimeUnit.SECONDS);

        Control.SCHEDULER.scheduleAtFixedRate(() -> {
            if (Groups.player.size() <= 0) {
                return;
            }
            renderServerLabels();
        }, 5, 5, TimeUnit.SECONDS);
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

    private void setupCustomServerDiscovery() {
        try {
            var providerField = Net.class.getDeclaredField("provider");
            providerField.setAccessible(true);
            var provider = (ArcNetProvider) providerField.get(Vars.net);
            var serverField = ArcNetProvider.class.getDeclaredField("server");
            serverField.setAccessible(true);
            var server = (Server) serverField.get(provider);

            server.setDiscoveryHandler((address, handler) -> {
                String name = mindustry.net.Administration.Config.serverName.string();
                String description = mindustry.net.Administration.Config.desc.string();
                String map = Vars.state.map.name();

                ByteBuffer buffer = ByteBuffer.allocate(500);

                int players = Groups.player.size();

                if (Config.IS_HUB && servers.size > 0) {
                    try {
                        var serverData = servers
                                .random();

                        var totalPlayers = servers.sum(s -> (int) s.getPlayers());

                        if (serverData != null) {
                            description += " -> " + serverData.getName();
                            map = serverData.getMapName() == null ? "" : serverData.getMapName();
                            players = totalPlayers;
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }

                writeString(buffer, name, 100);
                writeString(buffer, map, 64);

                buffer.putInt(Core.settings.getInt("totalPlayers", players));
                buffer.putInt(Vars.state.wave);
                buffer.putInt(Version.build);
                writeString(buffer, Version.type);

                buffer.put((byte) Vars.state.rules.mode().ordinal());
                buffer.putInt(Vars.netServer.admins.getPlayerLimit());

                writeString(buffer, description, 100);
                if (Vars.state.rules.modeName != null) {
                    writeString(buffer, Vars.state.rules.modeName, 50);
                }
                buffer.position(0);
                handler.respond(buffer);

                buffer.clear();
            });

        } catch (Throwable e) {
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

        var tapX = event.tile.worldx();
        var tapY = event.tile.worldy();

        Call.effectReliable(Fx.coreBuildShockwave, tapX, tapY, 0, Color.white);

        for (var core : serverCores) {
            var tapSize = core.getSize();

            if (tapX >= core.getX() - tapSize //
                    && tapX <= core.getX() + tapSize //
                    && tapY >= core.getY() - tapSize
                    && tapY <= core.getY() + tapSize//
            ) {
                if (core.getServer() == null) {
                    continue;
                }

                sessionHandler.get(event.player)
                        .ifPresent(session -> new ServerRedirectMenu().send(session, core.getServer()));
                break;
            }
        }
    }

    private void refreshServerList() {
        try {
            var request = new PaginationRequest()
                    .setPage(0)
                    .setSize(serverCores.size + 5);

            servers = Seq.with(apiGateway.getServers(request))
                    .select(server -> !server.getId().equals(Control.SERVER_ID));

            for (int i = 0; i < serverCores.size; i++) {
                var core = serverCores.get(i);

                if (i < servers.size) {
                    var data = servers.get(i);
                    core.setServer(data);
                } else {
                    core.setServer(null);
                }
            }

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void renderServerLabels() {
        var map = Vars.state.map;

        if (map == null) {
            return;
        }

        for (var core : serverCores) {
            renderServerLabel(core);
        }
    }

    private void renderServerLabel(ServerCore core) {
        ServerDto server = core.getServer();

        if (server == null) {
            return;
        }

        float labelX = core.getX();
        float labelY = core.getY();

        var mods = new ArrayList<>(server.getMods());

        mods.removeIf(m -> m.contains("mindustrytoolplugin") || m.contains("PluginLoader"));

        var name = server.getName();
        var description = server.getDescription();

        Utils.forEachPlayerLocale((locale, players) -> {

            String message = (server.getIsOfficial() ? "[gold]" + Iconc.star + "[] " : "") + newLine(name) + "[]\n" +
                    newLine(description) + "[]\n\n" +
                    "[#E3F2FD]Players: []" + server.getPlayers() + "\n" +
                    "[#BBDEFB]Map: []" + newLine(server.getMapName()) + "[]\n" +
                    "[#90CAF9]Mode: []" + server.getModeIcon() + " " + server.getMode() + "[]\n" +
                    "[#405AF9]Version: []" + server.getGameVersion() + "[]\n" +
                    (mods.isEmpty() ? "" : "[#4FC3F7]Mods:[] " + mods) + "[]\n\n" +
                    (server.getStatus().isOnline() ? "[accent]" : "[sky]") + I18n.t(locale, "@Tap to join server")
                    + "\n";

            for (var player : players) {
                Call.label(player.con, message, 5, labelX, labelY);
            }
        });
    }

    public String newLine(String text) {
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
