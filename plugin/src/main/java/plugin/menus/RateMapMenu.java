package plugin.menus;

import java.util.Locale;

import mindustry.gen.Call;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import plugin.handler.ApiGateway;
import plugin.handler.MapRating;
import plugin.type.Session;
import plugin.utils.Utils;

public class RateMapMenu extends PluginMenu<Map> {
    @Override
    public void build(Session session, Map map) {
        Locale locale = Utils.parseLocale(session.player.locale());

        this.title = ApiGateway.translate("Rate last map", locale);
        this.description = map.name();

        for (int i = 0; i < 5; i++) {
            int star = i + 1;

            option(MapRating.getStarDisplay(star), (p, s) -> {
                MapRating.updateMapRating(s, star);
                Call.sendMessage(
                        p.player.name() + " []voted " + star + "[accent]" + Iconc.star + " []on map " + map.name());
            });
            row();
        }
    }
}
