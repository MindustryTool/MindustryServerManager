package plugin.vote;

import plugin.menus.PluginMenu;

import arc.struct.Seq;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import plugin.core.Registry;
import plugin.utils.Tr;
import plugin.maprating.MapRating;
import plugin.session.Session;

public class RtvMenu extends PluginMenu<Integer> {
    private static final int MAPS_PER_PAGE = 7;

    @Override
    public void build(Session session, Integer page) {
        var voteHandler = Registry.get(RtvService.class);
        Seq<Map> maps = new Seq<>(voteHandler.getMaps());

        maps.sort((a, b) -> {
            var ar = MapRating.getStat(a).avgScore;
            var br = MapRating.getStat(b).avgScore;

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

        this.title = Tr.t(session.locale, "rtv.available_maps");
        this.description = Tr.t(session.locale, "rtv.page_description",
                "current", currentPage, "total", totalPages);

        int start = (currentPage - 1) * MAPS_PER_PAGE;
        int end = Math.min(start + MAPS_PER_PAGE, maps.size);

        for (int i = start; i < end; i++) {
            Map map = maps.get(i);
            var stats = MapRating.getStat(map);
            String ratingColor = MapRating.avgScoreColor(stats.avgScore);

            String voted = voteHandler.isVoted(session.player, map.file.nameWithoutExtension())
                    ? Tr.t(session.locale, "rtv.voted_badge")
                    : "";
            String text = String.format("%s%s%.2f [gold]%c []%s (%s)", voted, ratingColor, stats.avgScore, Iconc.star,
                    map.name(), String.valueOf(stats.totalVotes));

            option(text, (p, s) -> {
                voteHandler.handleVote(session.player, map);
            });
            row();
        }

        boolean hasPrev = currentPage > 1;
        boolean hasNext = currentPage < totalPages;

        if (hasPrev || hasNext) {
            if (hasPrev) {
                option(Tr.t(session.locale, "rtv.previous"), (p, s) -> {
                    new RtvMenu().send(p, currentPage - 1);
                });
            }

            if (hasNext) {
                option(Tr.t(session.locale, "rtv.next"), (p, s) -> {
                    new RtvMenu().send(p, currentPage + 1);
                });
            }
            row();
        }

        text(Tr.t(session.locale, "rtv.close"));
    }
}
