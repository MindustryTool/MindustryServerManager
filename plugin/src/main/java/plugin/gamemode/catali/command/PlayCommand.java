package plugin.gamemode.catali.command;

import plugin.Registry;
import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
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
        Registry.get(CataliGamemode.class).createTeam(session.player);
    }
}
