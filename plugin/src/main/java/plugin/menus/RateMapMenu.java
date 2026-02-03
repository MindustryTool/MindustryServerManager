package plugin.menus;

import java.util.Locale;

import mindustry.gen.Iconc;
import mindustry.maps.Map;
import plugin.handler.ApiGateway;
import plugin.handler.MapRating;
import plugin.type.Session;
import plugin.utils.Utils;

public class RateMapMenu extends PluginMenu<Map> {
    @Override
    public void build(Session session, Map map) {
        Locale locale = session.locale;

        this.title = ApiGateway.translate(locale, "@Rate last map");
        this.description = map.name();

        for (int i = 0; i < 5; i++) {
            int star = i + 1;

            option(MapRating.getStarDisplay(star), (p, s) -> {
                MapRating.updateMapRating(s, star);
                Utils.forEachPlayerLocale((l, players) -> {
                    String msg = ApiGateway.translate(l, p.player.name(), " ", "[]", "@voted ", star, "[accent]",
                            Iconc.star, " ", "[]", "@on map ", map.name());
                    for (var pp : players) {
                        pp.sendMessage(msg);
                    }
                });
            });
            row();
        }
    }
}
