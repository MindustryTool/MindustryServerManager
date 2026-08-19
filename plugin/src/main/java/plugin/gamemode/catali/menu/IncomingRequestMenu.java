package plugin.gamemode.catali.menu;

import lombok.RequiredArgsConstructor;
import mindustry.gen.Player;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.CataliGamemode;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
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

        title = Tr.t(session, "catali.incoming_join_request");
        description = Tr.t(session, "catali.accept_reject_request");

        option(Tr.t(session, "catali.accept"), (s, st) -> {
            team.joinRequests.remove(requester.uuid());
            team.members.add(requester.uuid());
            requester.team(team.team);
            requester.sendMessage(Tr.t(requester, "catali.join_accepted", "name", session.player.name));
        });

        option(Tr.t(session, "catali.reject"), (s, st) -> {
            team.joinRequests.remove(requester.uuid());
            requester.sendMessage(Tr.t(requester, "catali.join_rejected", "name", session.player.name));
        });

        row();
        option(Tr.t(session, "catali.close"), (s, st) -> {
        });
    }
}
