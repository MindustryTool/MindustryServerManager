package plugin.maprating;

import mindustry.Vars;
import mindustry.gen.Call;
import plugin.Cfg;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.session.Session;
import plugin.utils.Tr;
import plugin.vote.RtvMenu;

@Component
public class MapRatingCommands {

    private final MapRatingService mapRatingService;
    private final RtvMenu rtvMenu;

    public MapRatingCommands(MapRatingService mapRatingService, RtvMenu rtvMenu) {
        this.mapRatingService = mapRatingService;
        this.rtvMenu = rtvMenu;
    }

    @ClientCommand(name = "map", description = "Display current map info", admin = false)
    public void map(Session session) {
        if (Vars.state.map == null) {
            session.player.sendMessage(Tr.t(session.locale, "maprating.map_not_loaded"));
            return;
        }

        session.player.sendMessage(mapRatingService.getDisplayString(session.locale, Vars.state.map));
    }

    @ClientCommand(name = "maps", description = "List map", admin = false)
    public void maps(Session session, @Param(name = "page", required = false) Integer page) {
        rtvMenu.send(session, 0);
    }

    @ClientCommand(name = "submitmap", description = "Open discord channel", admin = false)
    public void submitmap(Session session) {
        Call.openURI(session.player.con, Cfg.DISCORD_INVITE_URL);
    }
}
