package plugin.commands.client;

import mindustry.Vars;
import plugin.Component;
import plugin.commands.PluginClientCommand;
import plugin.handler.MapRating;
import plugin.type.Session;
import plugin.handler.I18n;

@Component
public class MapCommand extends PluginClientCommand {
    public MapCommand() {
        setName("map");
        setDescription("Display current map info");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        if (Vars.state.map == null) {
            session.player.sendMessage(I18n.t(session.locale,
                    "@Map is not loaded"));
            return;
        }

        session.player.sendMessage(MapRating.getDisplayString(Vars.state.map));
    }
}
