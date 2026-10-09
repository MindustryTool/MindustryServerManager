package plugin.hub;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import plugin.session.SessionService;
import plugin.utils.Tr;

import java.util.ArrayList;

@Getter
@RequiredArgsConstructor
public class PlayerConnectHubEntry implements HubEntry {
    private final PlayerConnectRoom room;

    @Override
    public String getId() {
        return room.getRoomId() != null ? room.getRoomId() : "";
    }

    @Override
    public String getName() {
        if (room.getData() != null && room.getData().getName() != null && !room.getData().getName().isBlank()) {
            return room.getData().getName();
        }
        return room.getName() != null ? room.getName() : "Room " + getId();
    }

    @Override
    public int getPlayers() {
        if (room.getData() != null && room.getData().getPlayers() != null) {
            return room.getData().getPlayers().size();
        }
        return 0;
    }

    @Override
    public String renderLabel() {
        var data = room.getData();
        var mods = data != null && data.getMods() != null ? new ArrayList<>(data.getMods()) : new ArrayList<String>();
        mods.removeIf(m -> m.contains("Controller") || m.contains("PluginLoader"));

        String nameStr = getName();
        String mapName = data != null && data.getMapName() != null ? data.getMapName() : "";
        String gamemode = data != null && data.getGamemode() != null ? data.getGamemode() : "";
        String version = data != null && data.getVersion() != null ? data.getVersion() : "";

        boolean isSecured = data != null && data.isSecured();
        boolean isPrivate = data != null && data.isPrivate();

        StringBuilder badges = new StringBuilder("[coral][Room][white] ");
        if (isSecured) {
            badges.append("[gold]").append(Iconc.lock).append("[white] ");
        }
        if (isPrivate) {
            badges.append("[scarlet][Private][white] ");
        }

        String message = badges.toString() + HubService.newLine(nameStr) + "[white]\n\n"
                + "[#E3F2FD]Players: [white]" + getPlayers() + "\n"
                + (!mapName.isBlank() ? "[#BBDEFB]Map: [white]" + HubService.newLine(mapName) + "[white]\n" : "")
                + (!gamemode.isBlank() ? "[#90CAF9]Mode: [white]" + gamemode + "[white]\n" : "")
                + (!version.isBlank() ? "[#405AF9]Version: [white]" + version + "[white]\n" : "")
                + (mods.isEmpty() ? "" : "[#4FC3F7]Mods:[white] " + mods + "[white]\n")
                + "\n[accent]@Tap to join room\n";

        return message;
    }

    @Override
    public void onInteract(Player player, SessionService sessionService, HubService hubService) {
        hubService.hasPlayerConnect(player).thenAccept(hasMod -> {
            if (Boolean.TRUE.equals(hasMod)) {
                hubService.sendConnectPlayerConnect(player, room.getLink());
            } else {
                player.sendMessage(Tr.t(player, "hub.redirect.require_mod"));
            }
        });
    }
}
