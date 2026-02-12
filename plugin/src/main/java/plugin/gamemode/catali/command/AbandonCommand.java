package plugin.gamemode.catali.command;

import plugin.annotations.Gamemode;
import plugin.commands.PluginClientCommand;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.menu.AbandonMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
public class AbandonCommand extends PluginClientCommand {
    private final CataliGamemode gamemode;

    public AbandonCommand(CataliGamemode gamemode) {
        setName("a");
        setDescription("Abandon a unit or team");
        setAdmin(false);

        this.gamemode = gamemode;
    }

    @Override
    public void handle(Session session) {
        var team = gamemode.findTeam(session.player);

        if (team == null) {
            session.player.sendMessage(I18n.t(session, "@You are not in a team"));
            return;
        }

        new AbandonMenu(gamemode).send(session, team);
    }
}
