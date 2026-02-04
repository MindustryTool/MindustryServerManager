package plugin.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dto.ServerDto;
import dto.ServerStatus;
import mindustry.Vars;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Call;
import plugin.Config;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.event.SessionCreatedEvent;
import plugin.type.PaginationRequest;
import plugin.type.ServerCore;
import plugin.utils.ServerUtils;

public class HubHandler {

    private static final List<ServerCore> serverCores = new ArrayList<>();
    private static List<ServerDto> servers = new ArrayList<>();

    public static void init() {
        PluginEvents.on(TapEvent.class, HubHandler::onTap);
        PluginEvents.on(WorldLoadEvent.class, HubHandler::onWorldLoad);
        PluginEvents.on(SessionCreatedEvent.class, HubHandler::onSessionCreated);

        setupCustomServerDiscovery();

        ServerControl.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
            refreshServerList();
        }, 0, 30, TimeUnit.SECONDS);

        ServerControl.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
            renderServerLabels();
        }, 0, 5, TimeUnit.SECONDS);

    }

    private static void onWorldLoad(WorldLoadEvent event) {
        ServerControl.backgroundTask("Refresh server list", () -> {
            serverCores.clear();

            float centerX = Vars.world.unitWidth() / 2;
            float centerY = Vars.world.unitHeight() / 2;

            getTopServer();

            var cores = Team.sharded.cores();

            for (int i = 0; i < cores.size; i++) {
                var core = cores.get(i);
                serverCores.add(new ServerCore(servers.get(i), core.getX(), core.getY()));
            }

            serverCores.sort((a, b) -> Float.compare(
                    (a.getX() - centerX) * (a.getX() - centerX) + (a.getY() - centerY) * (a.getY() - centerY),
                    (b.getX() - centerX) * (b.getX() - centerX) + (b.getY() - centerY) * (b.getY() - centerY)));
        });
    }

    private static void onSessionCreated(SessionCreatedEvent event) {
        // var serverData = getTopServer();

        // if (serverData != null && !serverData.getId().equals(ServerControl.SERVER_ID)
        // && serverData.getPlayers() > 0) {
        // new ServerRedirectMenu().send(event.session, serverData);
        // }
    }

    private static synchronized ServerDto getTopServer() {
        try {
            var request = new PaginationRequest().setPage(0).setSize(Math.min(100, serverCores.size()));

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
                break;
            }
        }
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
}
