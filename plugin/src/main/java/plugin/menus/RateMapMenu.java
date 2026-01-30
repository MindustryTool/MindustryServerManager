package plugin.menus;

import java.util.Locale;

import mindustry.gen.Player;
import mindustry.maps.Map;
import plugin.handler.ApiGateway;
import plugin.handler.MapRating;
import plugin.utils.Utils;

public class RateMapMenu extends PluginMenu<Map> {
    @Override
    public void build(Player player, Map map) {
        Locale locale = Utils.parseLocale(player.locale());

        this.title = ApiGateway.translate("Rate last map", locale);
        this.description = map.name();

        for (int i = 0; i < 5; i++) {
            int star = i + 1;

            option(MapRating.getStarDisplay(star), (p, s) -> {
                MapRating.updateMapRating(s, star);
            });
            row();
        }
    }
}
