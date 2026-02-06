package plugin.menus;

import arc.struct.Seq;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import plugin.Registry;
import plugin.service.I18n;
import plugin.service.MapRating;
import plugin.service.VoteService;
import plugin.type.Session;

public class RtvMenu extends PluginMenu<Integer> {
    private static final int MAPS_PER_PAGE = 7;

    public RtvMenu() {
    }

    @Override
    public void build(Session session, Integer page) {
        var voteHandler = Registry.get(VoteService.class);
        Seq<Map> maps = new Seq<>(voteHandler.getMaps());

        maps.sort((a, b) -> {
            var ar = MapRating.getAvg(a);
            var br = MapRating.getAvg(b);

            if (ar == 0) {
                ar = 6;
            }

            if (br == 0) {
                br = 6;
            }

            return Float.compare(br, ar);
        });

        int totalPages = (int) Math.ceil((double) maps.size / MAPS_PER_PAGE);

        if (totalPages == 0) {
            totalPages = 1;
        }

        int currentPage = Math.max(1, Math.min(page, totalPages));

        this.title = I18n.t(session.locale, "@Available Maps");
        this.description = I18n.t(session.locale,
                "@Page ", currentPage, " / ", totalPages, "\n", "@Click a map to vote for it.");

        int start = (currentPage - 1) * MAPS_PER_PAGE;
        int end = Math.min(start + MAPS_PER_PAGE, maps.size);

        for (int i = start; i < end; i++) {
            Map map = maps.get(i);
            float rating = MapRating.getAvg(map);
            String ratingColor = MapRating.avgScoreColor(rating);

            String voted = voteHandler.isVoted(session.player, map.file.nameWithoutExtension())
                    ? I18n.t(session.locale, "[accent]", "@Voted")
                    : "";
            String text = String.format("%s%s%.2f [gold]%c []%s", voted, ratingColor, rating, Iconc.star,
                    map.name());

            option(text, (p, s) -> {
                voteHandler.handleVote(session.player, map);
            });
            row();
        }

        boolean hasPrev = currentPage > 1;
        boolean hasNext = currentPage < totalPages;

        if (hasPrev || hasNext) {
            if (hasPrev) {
                option(I18n.t(session.locale, "<<", "@Previous"), (p, s) -> {
                    new RtvMenu().send(p, currentPage - 1);
                });
            }

            if (hasNext) {
                option(I18n.t(session.locale, "@Next", ">>"), (p, s) -> {
                    new RtvMenu().send(p, currentPage + 1);
                });
            }
            row();
        }

        text(I18n.t(session.locale, "[scarlet]", "@Close"));
    }
}
