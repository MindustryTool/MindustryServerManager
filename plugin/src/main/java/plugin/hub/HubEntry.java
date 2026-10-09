package plugin.hub;

import mindustry.gen.Player;
import plugin.session.SessionService;

public interface HubEntry {
    String getId();

    String getName();

    int getPlayers();

    String renderLabel();

    void onInteract(Player player, SessionService sessionService, HubService hubService);
}
