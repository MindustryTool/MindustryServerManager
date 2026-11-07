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

    public record RtvMapEntry(Map map, MapRatingStat stat, float sortScore) {}

    private final RtvService rtvService;
    private final MapRatingService mapRatingService;

    public static Seq<RtvMapEntry> prepareSortedEntries(Seq<Map> maps, MapRatingService mapRatingService) {
        Seq<RtvMapEntry> entries = new Seq<>(maps.size);
        for (Map map : maps) {
            MapRatingStat stat = mapRatingService.getStat(map);
            float sortScore = (stat.avgScore == 0) ? 6.0f : stat.avgScore;
            entries.add(new RtvMapEntry(map, stat, sortScore));
        }

        entries.sort((a, b) -> {
            int scoreCmp = Float.compare(b.sortScore(), a.sortScore());
            if (scoreCmp != 0) {
                return scoreCmp;
            }
            int votesCmp = Integer.compare(b.stat().totalVotes, a.stat().totalVotes);
            if (votesCmp != 0) {
                return votesCmp;
            }
            String nameA = a.map() != null && a.map().name() != null ? a.map().name() : "";
            String nameB = b.map() != null && b.map().name() != null ? b.map().name() : "";
            return nameA.compareToIgnoreCase(nameB);
        });

        return entries;
    }

    @Override
    public void build(MenuContext<Integer> ctx) {
        Seq<RtvMapEntry> entries = prepareSortedEntries(rtvService.getMaps(), mapRatingService);

        this.title = Tr.t(ctx.getSession(), "rtv.available_maps");

        ctx.label("[accent]Select a map to vote for next match:");
        ctx.row();

        ctx.pane(MenuContext.SAFE_WIDTH, 320f, paneTable -> {
            for (RtvMapEntry entry : entries) {
                Map map = entry.map();
                MapRatingStat stats = entry.stat();
                String ratingColor = mapRatingService.avgScoreColor(stats.avgScore);
                boolean isVoted = rtvService.isVoted(ctx.getSession().player, map.file.nameWithoutExtension());

                TableBuilder card = UiBuilder.table().pad(4f);
                card.width(MenuContext.SAFE_CARD_WIDTH);
                String titleText = String.format("%s%s%.2f [gold]%c []%s",
                    isVoted ? "[accent]" + Tr.t(ctx.getSession(), "rtv.voted_badge") + " " : "",
                    ratingColor, stats.avgScore, Iconc.star, map.name());

                card.add(UiBuilder.label(titleText).wrap().align("left").growX());
                card.add(UiBuilder.label("[gray](" + stats.totalVotes + " votes)").align("right").padLeft(4f));
                card.row();

                String voteAct = ctx.registerAction((sess, st, res) -> rtvService.handleVote(sess.player, map));
                String voteBtnText = isVoted ? "[scarlet]Cancel Vote" : "[accent]Vote Map";
                card.add(UiBuilder.button(voteBtnText).clicked(voteAct).width(120f).height(34f).align("right").colspan(2).pad(2f));

                paneTable.add(card.growX().pad(2f)).row();
            }
        });
    }
}
