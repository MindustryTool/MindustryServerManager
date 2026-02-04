package plugin.handler;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.net.Server;
import arc.struct.Seq;
import arc.util.Log;
import dto.ServerDto;
import dto.ServerStatus;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net;
import plugin.Config;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.event.SessionCreatedEvent;
import plugin.menus.ServerRedirectMenu;
import plugin.type.PaginationRequest;
import plugin.type.ServerCore;

public class HubHandler {

    private static final Seq<ServerCore> serverCores = new Seq<>();
    private static Seq<ServerDto> servers = new Seq<>();

    public static void init() {
        PluginEvents.on(TapEvent.class, HubHandler::onTap);
        PluginEvents.on(WorldLoadEvent.class, HubHandler::onWorldLoad);
        PluginEvents.on(SessionCreatedEvent.class, HubHandler::onSessionCreated);

        setupCustomServerDiscovery();

        ServerControl.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
            refreshServerList();
        }, 10, 30, TimeUnit.SECONDS);

        ServerControl.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
            renderServerLabels();
        }, 10, 5, TimeUnit.SECONDS);

    }

    private static void onWorldLoad(WorldLoadEvent event) {
        ServerControl.backgroundTask("Refresh server list", () -> {
            serverCores.clear();

            float centerX = Vars.world.unitWidth() / 2;
            float centerY = Vars.world.unitHeight() / 2;

            var cores = Team.sharded.cores().sort((a, b) -> Float.compare(
                    (a.getX() - centerX) * (a.getX() - centerX) + (a.getY() - centerY) * (a.getY() - centerY),
                    (b.getX() - centerX) * (b.getX() - centerX) + (b.getY() - centerY) * (b.getY() - centerY)));

            for (int i = 0; i < cores.size; i++) {
                var core = cores.get(i);
                serverCores.add(new ServerCore(null, core.getX(), core.getY()));
            }
        });
    }

    private static void onSessionCreated(SessionCreatedEvent event) {
        // var serverData = getTopServer();

        // if (serverData != null && !serverData.getId().equals(ServerControl.SERVER_ID)
        // && serverData.getPlayers() > 0) {
        // new ServerRedirectMenu().send(event.session, serverData);
        // }
    }

    private static void setupCustomServerDiscovery() {
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
                                .select(s -> s.getStatus() == ServerStatus.STOP || s.getStatus() == ServerStatus.ONLINE)
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

    private static void writeString(ByteBuffer buffer, String string) {
        writeString(buffer, string, 32);
    }

    private static void writeString(ByteBuffer buffer, String string, int maxlen) {
        byte[] bytes = string.getBytes(Vars.charset);
        if (bytes.length > maxlen) {
            bytes = Arrays.copyOfRange(bytes, 0, maxlen);
        }

        buffer.put((byte) bytes.length);
        buffer.put(bytes);
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
                if (core.getServer() == null) {
                    continue;
                }

                SessionHandler.get(event.player)
                        .ifPresent(session -> new ServerRedirectMenu().send(session, core.getServer()));
                break;
            }
        }
    }

    private static void refreshServerList() {
        try {
            var request = new PaginationRequest()
                    .setPage(0)
                    .setSize(serverCores.size);

            servers = Seq.with(ApiGateway.getServers(request));

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

    private static void renderServerLabels() {
        var map = Vars.state.map;

        if (map == null) {
            return;
        }

        for (var core : serverCores) {
            renderServerLabel(core);
        }
    }

    private static void renderServerLabel(ServerCore core) {
        ServerDto server = core.getServer();

        if (server == null) {
            return;
        }

        float labelX = core.getX();
        float labelY = core.getY();

        var mods = new ArrayList<>(server.getMods());

        mods.removeIf(m -> m.contains("mindustrytoolplugin") || m.contains("PluginLoader"));

        var name = server.getName().substring(0, Math.min(50, server.getName().length()));
        var description = server.getDescription().substring(0, Math.min(50, server.getDescription()
                .length()));

        String message = name + "[]\n" +
                description + "[]\n\n" +
                "[#E3F2FD]Players: []" + server.getPlayers() + "\n" +
                "[#BBDEFB]Map: []" + server.getMapName() + "[]\n" +
                "[#90CAF9]Mode: []" + server.getMode() + "[]\n" +
                (mods.isEmpty() ? "" : "[#4FC3F7]Mods:[] " + mods) + "[]\n";

        Call.label(message, 5, labelX, labelY);
    }
}
