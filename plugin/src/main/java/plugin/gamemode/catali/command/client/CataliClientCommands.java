package plugin.gamemode.catali.command.client;

import mindustry.game.Team;
import plugin.PluginEvents;
import plugin.annotations.ClientCommand;
import plugin.annotations.Gamemode;
import plugin.annotations.Param;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.event.ExpGainEvent;
import plugin.gamemode.catali.menu.AbandonMenu;
import plugin.gamemode.catali.menu.CommonUpgradeMenu;
import plugin.gamemode.catali.menu.RareUpgradeMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
public class CataliClientCommands {

    private final CataliGamemode gamemode;

    public CataliClientCommands(CataliGamemode gamemode) {
        this.gamemode = gamemode;
    }

    @ClientCommand(name = "a", description = "Abandon a unit or team", admin = false)
    public void abandon(Session session) {
        var team = gamemode.findTeam(session.player);

        if (team == null) {
            session.player.sendMessage(I18n.t(session, "@You are not in a team"));
            return;
        }

        new AbandonMenu(gamemode).send(session, team);
    }

    @ClientCommand(name = "addexp", description = "Add experience points to a unit", admin = true)
    public void addExp(Session session, @Param(name = "amount") Integer amount) {
        if (amount < 0) {
            session.player.sendMessage(I18n.t(session, "@Amount must be positive"));
            return;
        }

        Team team = session.player.team();
        var teamData = gamemode.findTeam(team);

        if (teamData == null) {
            session.player.sendMessage(I18n.t(session, "@You are not in a team"));
            return;
        }

        PluginEvents.fire(new ExpGainEvent(teamData, amount, 0, 0));
    }

    @ClientCommand(name = "p", description = "Start playing in Catali gamemode", admin = false)
    public void play(Session session) {
        if (gamemode.canRespawn(session.player)) {
            var team = gamemode.createTeam(session.player);

            if (session.player.unit() == null) {
                gamemode.assignUnitForPlayer(team, session.player);
            }
        }
    }

    @ClientCommand(name = "u", description = "Show upgrade menu", admin = false)
    public void upgrade(Session session) {
        var player = session.player;
        var team = gamemode.findTeam(player);

        if (team == null) {
            session.player.sendMessage(I18n.t(player, "@Use", "[accent]/p[white]", "@to start a new team"));
        } else {
            var showed = false;
            if (team.level.commonUpgradePoints > 0) {
                new CommonUpgradeMenu().send(session, team);
                showed = true;
            }

            if (team.level.rareUpgradePoints > 0) {
                new RareUpgradeMenu().send(session, team);
                showed = true;
            }

            if (!showed) {
                session.player.sendMessage(I18n.t(player, "@You have no upgrade points"));
            }
        }
    }
}
