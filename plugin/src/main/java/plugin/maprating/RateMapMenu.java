package plugin.maprating;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class RateMapMenu extends ServerMenu<Map> {

    private final MapRatingService mapRatingService;

    @Override
    public void build(MenuContext<Map> ctx) {
        Map map = ctx.getState();
        if (map == null) {
            return;
        }

        Locale locale = ctx.getSession().locale;
        this.title = Tr.t(locale, "maprating.rate_title");

        ctx.label(Tr.t(ctx.getSession(), "maprating.map_label", "map", map.name())).width(MenuContext.SAFE_WIDTH);
        ctx.row();

        ctx.table(starsTable -> {
            for (int i = 5; i > 0; i--) {
                int star = i;
                String starDisplay = mapRatingService.getStarDisplay(star);
                String act = ctx.registerAction((sess, st, res) -> {
                    mapRatingService.updateMapRating(map, star);
                    Utils.forEachPlayerLocale((l, players) -> {
                        String msg = Tr.t(l, "maprating.voted",
                            "player", sess.player.name(), "star", star, "icon", Iconc.star, "map", map.name());
                        for (var pp : players) {
                            pp.sendMessage(msg);
                        }
                    });
                });

                starsTable.add(UiBuilder.button(starDisplay).clicked(act).growX().height(34f).pad(2f)).row();
            }
        }).growX();
    }
}
