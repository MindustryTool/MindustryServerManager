package plugin.gamemode.catali.menu;

import lombok.RequiredArgsConstructor;
import mindustry.gen.Player;
import plugin.annotations.Gamemode;
import plugin.gamemode.catali.CataliGamemode;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
@RequiredArgsConstructor
public class IncomingRequestMenu extends PluginMenu<Player> {

    private final CataliGamemode gamemode;

    @Override
    public void build(Session session, Player requester) {
        if (requester == null)
            return;

        var team = gamemode.findTeam(session.player);

        if (team == null)
            return;

        title = I18n.t(session, "@Incoming Join Request");
        description = I18n.t(session, "@Accept or reject the join request.");

        option(I18n.t(session, "@Accept"), (s, st) -> {
            team.joinRequests.remove(requester.uuid());
            team.members.add(requester.uuid());
            requester.team(team.team);
            requester.sendMessage(I18n.t(requester, "@Your request to join @'s team was accepted."));
        });

        option(I18n.t(session, "@Reject"), (s, st) -> {
            team.joinRequests.remove(requester.uuid());
            requester.sendMessage(I18n.t(requester, "@Your request to join @'s team was rejected."));
        });

        row();
        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
