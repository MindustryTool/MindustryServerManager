package plugin.commands.client;

import mindustry.Vars;
import plugin.commands.PluginCommand;
import plugin.handler.MapRating;
import plugin.type.Session;
import plugin.handler.ApiGateway;

public class MapCommand extends PluginCommand {
    public MapCommand() {
        setName("map");
        setDescription("Display current map info");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        if (Vars.state.map == null) {
            session.player.sendMessage(ApiGateway.translate(session.locale,
                    "@Map is not loaded"));
            return;
        }

        session.player.sendMessage(MapRating.getDisplayString(Vars.state.map));
    }
}
