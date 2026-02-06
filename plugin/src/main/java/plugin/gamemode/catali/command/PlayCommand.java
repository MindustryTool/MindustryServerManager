package plugin.gamemode.catali.command;

import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliGamemode;
import plugin.type.Session;

@Component
public class PlayCommand extends PluginClientCommand {
    public PlayCommand() {
        setName("play");
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
