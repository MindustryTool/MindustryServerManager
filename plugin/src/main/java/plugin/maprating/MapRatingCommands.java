package plugin.maprating;

import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.maps.Map;
import plugin.Cfg;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
public class MapRatingCommands {

    @ClientCommand(name = "map", description = "Display current map info", admin = false)
    public void map(Session session) {
        if (Vars.state.map == null) {
            session.player.sendMessage(Tr.t(session.locale, "maprating.map_not_loaded"));
            return;
        }

        session.player.sendMessage(MapRating.getDisplayString(session.locale, Vars.state.map));

    }

    @ClientCommand(name = "maps", description = "List map", admin = false)
    public void maps(Session session, @Param(name = "page", required = false) Integer page) {
        int pageSize = 5;
        if (page != null) {
            page = 0;
        }

        int start = page * pageSize;
        int end = start + pageSize;
        int maxSize = Vars.maps.customMaps().size;

        if (start >= maxSize) {
            session.player.sendMessage(Tr.t(session.locale, "maprating.no_more_maps"));
            return;
        }

        if (end > maxSize) {
            end = maxSize;
        }

        StringBuilder sb = new StringBuilder(Tr.t(session, "maprating.id")).append("\n");
        for (int i = start; i < end; i++) {
            Map map = Vars.maps.customMaps().get(i);
            sb.append(i)
                    .append(" ")
                    .append(map.name())
                    .append("")
                    .append(MapRating.getAvgString(map))
                    .append("\n");
        }
        session.player.sendMessage(sb.toString());
    }

    @ClientCommand(name = "submitmap", description = "Open discord channel", admin = false)
    public void submitmap(Session session) {
        Call.openURI(session.player.con, Cfg.DISCORD_INVITE_URL);
    }
}