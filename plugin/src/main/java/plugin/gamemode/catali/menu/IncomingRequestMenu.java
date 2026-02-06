package plugin.gamemode.catali.menu;

import mindustry.gen.Player;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliGamemode;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class IncomingRequestMenu extends PluginMenu<Player> {

    @Override
    public void build(Session session, Player requester) {
        if (requester == null) return;
        
        var gamemode = Registry.get(CataliGamemode.class);
        var team = gamemode.findTeam(session.player);
        
        if (team == null) return;

        title = I18n.t(session, "@Incoming Join Request", session.player);
        description = I18n.t(session, "@Accept or reject the join request.", session.player);

        option(I18n.t(session, "@Accept", session.player), (s, st) -> {
            team.joinRequests.remove(requester.uuid());
            team.members.add(requester.uuid());
            requester.team(team.team);
            requester.sendMessage(I18n.t(requester, "@Your request to join @'s team was accepted.", session.player));
        });
        
        option(I18n.t(session, "@Reject", session.player), (s, st) -> {
             team.joinRequests.remove(requester.uuid());
             requester.sendMessage(I18n.t(requester, "@Your request to join @'s team was rejected.", session.player));
        });
        
        row();
        option(I18n.t(session, "@Close", session.player), (s, st) -> {});
    }
}
