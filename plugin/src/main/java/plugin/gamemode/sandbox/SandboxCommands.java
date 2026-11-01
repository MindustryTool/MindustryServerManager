package plugin.gamemode.sandbox;

import java.util.Objects;

import mindustry.game.Team;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Param;
import plugin.gamemode.GamemodeCondition;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@ConditionOn(value = GamemodeCondition.class, args = {"sandbox"})
public class SandboxCommands {

    @ClientCommand(name = "team", description = "Open team selection menu or change team directly", admin = false)
    public void team(Session session, @Param(name = "name|id", required = false) String teamName) {
        Objects.requireNonNull(session);

        if (teamName == null || teamName.trim().isEmpty()) {
            new SandboxTeamMenu().send(session, null);
            return;
        }

        Team targetTeam = parseTeam(teamName.trim());
        if (targetTeam == null) {
            session.player.sendMessage(Tr.t(session, "sandbox.team_not_found", "input", teamName));
            return;
        }

        session.player.team(targetTeam);
        session.player.sendMessage(Tr.t(session, "sandbox.team_changed", "team", targetTeam.coloredName()));
    }

    private Team parseTeam(String input) {
        try {
            int id = Integer.parseInt(input);
            for (Team team : SandboxTeamMenu.PLAYABLE_TEAMS) {
                if (team.id == id) {
                    return team;
                }
            }
        } catch (NumberFormatException ignored) {
        }

        for (Team team : SandboxTeamMenu.PLAYABLE_TEAMS) {
            if (team.name.equalsIgnoreCase(input)) {
                return team;
            }
        }

        return null;
    }
}
