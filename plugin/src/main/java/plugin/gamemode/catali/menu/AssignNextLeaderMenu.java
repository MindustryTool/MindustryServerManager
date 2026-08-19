package plugin.gamemode.catali.menu;

import arc.struct.Seq;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class AssignNextLeaderMenu extends PluginMenu<CataliTeamData> {

    @Override
    public void build(Session session, CataliTeamData team) {
        var availableTeammates = new Seq<Player>();
        for (var uuid : team.members) {
            if (uuid.equals(session.player.uuid()) || uuid.equals(team.nextLeaderUuid)) {
                continue;
            }
            var p = Groups.player.find(pl -> pl.uuid().equals(uuid));
            if (p != null)
                availableTeammates.add(p);
        }

        if (availableTeammates.isEmpty()) {
            title = Tr.t(session, "catali.no_teammates");
            return;
        }

        title = Tr.t(session, "catali.assign_next_leader");
        description = Tr.t(session, "catali.select_next_leader");

        int i = 0;
        for (var p : availableTeammates) {
            if (i > 0 && i % 2 == 0)
                row();

            option(p.name(), (s, st) -> {
                team.assignNextLeader(p.uuid());
                s.player.sendMessage(Tr.t(s, "catali.leadership_transferred", "name", p.name()));
            });
            i++;
        }

        row();
        text(Tr.t(session, "catali.close"));
    }
}
