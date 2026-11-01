package plugin.gamemode.sandbox;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import mindustry.game.Team;
import plugin.menus.PluginMenu;
import plugin.session.Session;
import plugin.utils.Tr;

public class SandboxTeamMenu extends PluginMenu<Void> {

    public static final List<Team> PLAYABLE_TEAMS = Arrays.asList(
            Team.sharded,
            Team.crux,
            Team.malis,
            Team.green,
            Team.blue,
            Team.neoplastic
    );

    @Override
    protected void build(Session session, Void state) {
        Objects.requireNonNull(session);

        title = Tr.t(session, "sandbox.team_menu_title");
        description = Tr.t(session, "sandbox.team_menu_description");

        int count = 0;
        for (Team team : PLAYABLE_TEAMS) {
            String label = team.coloredName();
            option(label, (s, st) -> {
                s.player.team(team);
                s.player.sendMessage(Tr.t(s, "sandbox.team_changed", "team", team.coloredName()));
            });
            count++;
            if (count % 2 == 0) {
                row();
            }
        }

        if (count % 2 != 0) {
            row();
        }
        text(Tr.t(session, "sandbox.close"));
    }
}
