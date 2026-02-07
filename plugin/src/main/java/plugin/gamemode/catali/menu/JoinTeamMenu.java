package plugin.gamemode.catali.menu;

import arc.struct.Seq;
import mindustry.gen.Groups;
import plugin.annotations.Gamemode;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.service.SessionHandler;
import plugin.type.Session;

@Gamemode("catali")
public class JoinTeamMenu extends PluginMenu<Void> {

    @Override
    public void build(Session session, Void state) {
        var gamemode = Registry.get(CataliGamemode.class);
        var availableTeams = gamemode.getAllTeams().select(
                t -> !t.joinRequests.contains(session.player.uuid()) && !t.members.contains(session.player.uuid()));

        var validTeams = new Seq<CataliTeamData>();
        for (var t : availableTeams) {
            var leader = Groups.player.find(p -> p.uuid().equals(t.leaderUuid));
            if (leader != null) {
                validTeams.add(t);
            }
        }

        title = I18n.t(session, "@Join Team");
        description = I18n.t(session, "@Select a team leader to request joining.");

        int i = 0;
        for (var team : validTeams) {
            var leader = Groups.player.find(p -> p.uuid().equals(team.leaderUuid));
            if (leader == null)
                continue;

            if (i > 0 && i % 2 == 0)
                row();

            option(leader.name(), (s, st) -> {
                team.joinRequests.add(s.player.uuid());
                var leaderP = Groups.player.find(p -> p.uuid().equals(team.leaderUuid));
                if (leaderP != null) {
                    Registry.get(SessionHandler.class).get(leaderP).ifPresent(leaderSession -> {
                        Registry.get(IncomingRequestMenu.class).send(leaderSession, s.player);
                    });
                }
                s.player.sendMessage(I18n.t(s, "@Request sent to @", leaderP));
            });
            i++;
        }

        row();
        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
