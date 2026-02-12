package plugin.gamemode.catali.command.client;

import mindustry.game.Team;
import plugin.PluginEvents;
import plugin.commands.PluginClientCommand;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.event.ExpGainEvent;
import plugin.service.I18n;
import plugin.type.Session;

public class AddExpCommand extends PluginClientCommand {

    private final Param amountParam;
    private final CataliGamemode gamemode;

    public AddExpCommand(CataliGamemode gamemode) {
        setName("addexp");
        setDescription("Add experience points to a unit");
        setAdmin(true);

        this.gamemode = gamemode;

        amountParam = required("amount").min(0);
    }

    @Override
    public void handle(Session session) {
        Team team = session.player.team();
        int amount = amountParam.asInt();
        var teamData = gamemode.findTeam(team);

        if (teamData == null) {
            session.player.sendMessage(I18n.t(session, "@You are not in a team"));
            return;
        }

        PluginEvents.fire(new ExpGainEvent(teamData, amount, 0, 0));
    }
}
