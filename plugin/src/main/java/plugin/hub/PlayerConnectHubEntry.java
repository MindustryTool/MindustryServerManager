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

        StringBuilder badges = new StringBuilder("[coral][Room][white] ");
        if (data != null && data.isSecured()) {
            badges.append("[gold]").append(Iconc.lock).append("[white] ");
        }

        String region = room.getName();

        StringBuilder sb = new StringBuilder();
        sb.append(badges).append(HubService.newLine(nameStr)).append("[white]\n");
        if (region != null && !region.isBlank()) {
            sb.append("[#B2DFDB]Region: [white]").append(region).append("\n");
        }
        sb.append("\n");

        sb.append("[#E3F2FD]Players: [white]").append(getPlayers()).append("\n");
        if (!mapName.isBlank()) {
            sb.append("[#BBDEFB]Map: [white]").append(HubService.newLine(mapName)).append("[white]\n");
        }
        if (!gamemode.isBlank()) {
            sb.append("[#90CAF9]Mode: [white]").append(gamemode).append("[white]\n");
        }
        if (!version.isBlank()) {
            sb.append("[#405AF9]Version: [white]").append(version).append("[white]\n");
        }

        if (data != null) {
            double ping = data.getPing();
            if (ping >= 0 && ping <= 999) {
                sb.append("[#FFCC80]Ping: [white]").append((int) ping).append("ms\n");
            } else if (ping > 999) {
                sb.append("[scarlet]Ping: [scarlet]>999ms\n");
            }

            if (data.getLocale() != null && !data.getLocale().isBlank() && !"unknown".equalsIgnoreCase(data.getLocale())) {
                sb.append("[#CE93D8]Locale: [white]").append(data.getLocale()).append("\n");
            }

            if (data.getCreatedAt() > 0) {
                sb.append("[#FFE082]Created: [white]").append(formatRelativeTime(data.getCreatedAt())).append("\n");
            }
        }

        if (!mods.isEmpty()) {
            sb.append("[#4FC3F7]Mods:[white] ").append(mods).append("[white]\n");
        }
        sb.append("\n[accent]@Tap to join room\n");

        return sb.toString();
    }

    private String formatRelativeTime(long timestamp) {
        long elapsedMillis = Math.max(0, System.currentTimeMillis() - timestamp);
        long minutes = elapsedMillis / 60000;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        if (hours < 24) {
            return remainingMinutes > 0 ? hours + "h " + remainingMinutes + "m ago" : hours + "h ago";
        }
        long days = hours / 24;
        return days + "d ago";
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
