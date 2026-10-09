package plugin.hub;

import common.server.Server;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import plugin.session.SessionService;

import java.util.ArrayList;

@Getter
@RequiredArgsConstructor
public class ServerHubEntry implements HubEntry {
    private final Server server;

    @Override
    public String getId() {
        return server.getId() != null ? server.getId().toString() : "";
    }

    @Override
    public String getName() {
        return server.getName();
    }

    @Override
    public int getPlayers() {
        return (int) server.getPlayers();
    }

    @Override
    public String renderLabel() {
        var mods = new ArrayList<>(server.getMods());
        mods.removeIf(m -> m.contains("Controller") || m.contains("PluginLoader"));

        var name = server.getName();
        var description = server.getDescription();

        String message = (Boolean.TRUE.equals(server.getIsOfficial()) ? "[gold]" + Iconc.star + "[white] " : "")
                + HubService.newLine(name) + "[white]\n"
                + HubService.newLine(description) + "[white]\n\n"
                + "[#E3F2FD]Players: [white]" + server.getPlayers() + "\n"
                + "[#BBDEFB]Map: [white]" + HubService.newLine(server.getMapName()) + "[white]\n"
                + "[#90CAF9]Mode: [white]" + server.getModeIcon() + " " + server.getMode() + "[white]\n"
                + "[#405AF9]Version: [white]" + server.getGameVersion() + "[white]\n"
                + (mods.isEmpty() ? "" : "[#4FC3F7]Mods:[white] " + HubService.formatMods(mods)) + "[white]\n\n"
                + (server.getStatus() != null && server.getStatus().isOnline() ? "[accent]" : "[sky]")
                + "@Tap to join server\n";

        return message;
    }

    @Override
    public void onInteract(Player player, SessionService sessionService, HubService hubService) {
        sessionService.get(player).ifPresent(session -> new ServerRedirectMenu().send(session, server));
    }
}
