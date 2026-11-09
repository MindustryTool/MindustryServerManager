package plugin.maprating;

import java.util.Locale;
import arc.struct.Seq;
import arc.util.Scaling;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;
import plugin.utils.Utils;
import plugin.vote.RtvMenu;

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

        if (ctx.getSession() != null && ctx.getSession().player != null && map.file != null) {
            RtvMenu.streamMapTextures(ctx.getSession().player, Seq.with(map));
        }

        Locale locale = ctx.getSession().locale;
        this.title = Tr.t(locale, "maprating.rate_title");

        String mapId = map.file != null ? map.file.nameWithoutExtension() : "unknown";
        MapRatingStat stats = mapRatingService.getStat(map);
        String ratingColor = mapRatingService.avgScoreColor(stats.avgScore);

        TableBuilder card = UiBuilder.table().background("pane").margin(12f).align("center");
        card.width(ctx.safeCardWidth());

        card.add(UiBuilder.image("net-map-" + mapId)
                .placeholder("nomap")
                .scaling(Scaling.fit)
                .width(200f)
                .height(115f)
                .align("center"))
            .row();

        card.add(UiBuilder.label("[white]" + map.name())
                .wrap()
                .align("center")
                .labelAlign("center")
                .growX()
                .padTop(6f))
            .row();

        if (map.author() != null && !map.author().trim().isEmpty()) {
            card.add(UiBuilder.label(Tr.t(locale, "maprating.author", "author", map.author()))
                    .wrap()
                    .align("center")
                    .labelAlign("center")
                    .growX()
                    .padTop(2f))
                .row();
        }

        String ratingText = String.format("%s%.2f [gold]%c [gray](%d)",
                ratingColor, stats.avgScore, Iconc.star, stats.totalVotes);
        card.add(UiBuilder.label(ratingText)
                .align("center")
                .labelAlign("center")
                .growX()
                .padTop(2f))
            .row();

        ctx.add(card);
        ctx.row();

        ctx.table(starsTable -> {
            starsTable.width(ctx.safeCardWidth()).align("center");
            for (int i = 1; i <= 5; i++) {
                int star = i;
                String starText = "[gold]" + Iconc.star + " [white]" + star;
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

                starsTable.add(UiBuilder.button(starText)
                        .clicked(act)
                        .height(36f)
                        .growX()
                        .pad(1f));
            }
        }).padTop(6f);
    }
}
