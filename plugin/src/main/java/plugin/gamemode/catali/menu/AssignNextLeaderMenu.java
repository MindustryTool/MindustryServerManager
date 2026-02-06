package plugin.gamemode.catali.menu;

import arc.struct.Seq;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.annotations.Component;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
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
            title = I18n.t(session, "@No available teammates to transfer leadership to.", session.player);
            return;
        }

        title = I18n.t(session, "@Assign Next Leader", session.player);
        description = I18n.t(session, "@Select a teammate to be the next leader.", session.player);

        int i = 0;
        for (var p : availableTeammates) {
            if (i > 0 && i % 2 == 0)
                row();

            option(p.name(), (s, st) -> {
                team.assignNextLeader(p.uuid());
                s.player.sendMessage(I18n.t(s, "@Leadership transferred to @", p));
            });
            i++;
        }

        row();
        text(I18n.t(session, "@Close", session.player));
    }
}
