package plugin.gamemode.catali.command;

import plugin.annotations.Gamemode;
import plugin.commands.PluginClientCommand;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliGamemode;
import plugin.type.Session;

@Gamemode("catali")
public class PlayCommand extends PluginClientCommand {
    public PlayCommand() {
        setName("p");
        setDescription("Start playing in Catali gamemode");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        var gamemode = Registry.get(CataliGamemode.class);
        var team = gamemode.createTeam(session.player);

        if (session.player.unit() == null) {
            gamemode.assignUnitForPlayer(team, session.player);
        }
    }
}
