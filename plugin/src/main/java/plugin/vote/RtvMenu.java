package plugin.vote;

import arc.struct.Seq;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.maprating.MapRatingService;
import plugin.maprating.MapRatingStat;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class RtvMenu extends ServerMenu<Integer> {

    private final RtvService rtvService;
    private final MapRatingService mapRatingService;

    @Override
    public void build(MenuContext<Integer> ctx) {
        Seq<Map> maps = new Seq<>(rtvService.getMaps());

        maps.sort((a, b) -> {
            var ar = mapRatingService.getStat(a).avgScore;
            var br = mapRatingService.getStat(b).avgScore;
            if (ar == 0) ar = 6;
            if (br == 0) br = 6;
            return Float.compare(br, ar);
        });

        this.title = Tr.t(ctx.getSession(), "rtv.available_maps");

        ctx.label("[accent]Select a map to vote for next match:");
        ctx.row();

        ctx.pane(MenuContext.SAFE_WIDTH, 320f, paneTable -> {
            for (Map map : maps) {
                MapRatingStat stats = mapRatingService.getStat(map);
                String ratingColor = mapRatingService.avgScoreColor(stats.avgScore);
                boolean isVoted = rtvService.isVoted(ctx.getSession().player, map.file.nameWithoutExtension());

                TableBuilder card = UiBuilder.table().pad(4f);
                String titleText = String.format("%s%s%.2f [gold]%c []%s",
                    isVoted ? "[accent]" + Tr.t(ctx.getSession(), "rtv.voted_badge") + " " : "",
                    ratingColor, stats.avgScore, Iconc.star, map.name());

                card.add(UiBuilder.label(titleText).align("left"));
                card.add(UiBuilder.label("[gray](" + stats.totalVotes + " votes)").align("right"));
                card.row();

                String voteAct = ctx.registerAction((sess, st, res) -> rtvService.handleVote(sess.player, map));
                String voteBtnText = isVoted ? "[scarlet]Cancel Vote" : "[accent]Vote Map";
                card.add(UiBuilder.button(voteBtnText).clicked(voteAct).width(130f).height(38f).align("right").colspan(2).pad(2f));

                paneTable.add(card.growX().pad(2f)).row();
            }
        });

        ctx.row();
        ctx.closeButton(Tr.t(ctx.getSession(), "rtv.btn_close"));
    }
}
