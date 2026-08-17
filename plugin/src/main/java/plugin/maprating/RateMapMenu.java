package plugin.maprating;

import plugin.menus.PluginMenu;

import java.util.Locale;

import mindustry.gen.Iconc;
import mindustry.maps.Map;
import plugin.utils.Tr;
import plugin.session.Session;
import plugin.utils.Utils;

public class RateMapMenu extends PluginMenu<Map> {

    @Override
    public void build(Session session, Map map) {
        Locale locale = session.locale;

        this.title = Tr.t(locale, "maprating.rate_title");
        this.description = map.name();

        for (int i = 5; i > 0; i--) {
            int star = i;

            option(MapRating.getStarDisplay(star), (p, s) -> {
                MapRating.updateMapRating(s, star);
                Utils.forEachPlayerLocale((l, players) -> {
                    String msg = Tr.t(l, "maprating.voted",
                            "player", p.player.name(), "star", star, "icon", Iconc.star, "map", map.name());
                    for (var pp : players) {
                        pp.sendMessage(msg);
                    }
                });
            });
            row();
        }
    }
}
