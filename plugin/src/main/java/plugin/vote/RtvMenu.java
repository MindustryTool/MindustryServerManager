package plugin.vote;

import arc.struct.Seq;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import plugin.maprating.MapRatingService;
import plugin.maprating.MapRatingStat;
import plugin.menus.PluginMenu;
import plugin.session.Session;
import plugin.utils.Tr;

public class RtvMenu extends PluginMenu<Integer> {
    private static final int MAPS_PER_PAGE = 5;

    private final RtvService rtvService;
    private final MapRatingService mapRatingService;

    public RtvMenu(RtvService rtvService, MapRatingService mapRatingService) {
        this.rtvService = rtvService;
        this.mapRatingService = mapRatingService;
    }

    @Override
    public void build(Session session, Integer page) {
        Seq<Map> maps = new Seq<>(rtvService.getMaps());

        maps.sort((a, b) -> {
            var ar = mapRatingService.getStat(a).avgScore;
            var br = mapRatingService.getStat(b).avgScore;

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

        this.title = Tr.t(session, "rtv.available_maps");
        this.description = Tr.t(session, "rtv.page_description",
                "current", currentPage, "total", totalPages);

        int start = (currentPage - 1) * MAPS_PER_PAGE;
        int end = Math.min(start + MAPS_PER_PAGE, maps.size);

        for (int i = start; i < end; i++) {
            Map map = maps.get(i);
            MapRatingStat stats = mapRatingService.getStat(map);
            String ratingColor = mapRatingService.avgScoreColor(stats.avgScore);

            String voted = rtvService.isVoted(session.player, map.file.nameWithoutExtension())
                    ? Tr.t(session, "rtv.voted_badge")
                    : "";
            String text = String.format("%s%s%.2f [gold]%c []%s (%s)", voted, ratingColor, stats.avgScore, Iconc.star,
                    map.name(), String.valueOf(stats.totalVotes));

            option(text, (p, s) -> {
                rtvService.handleVote(session.player, map);
            });
            row();
        }

        if (totalPages > 1) {
            if (currentPage > 1) {
                option(Tr.t(session, "rtv.previous_page"), (p, s) -> {
                    new RtvMenu(rtvService, mapRatingService).send(p, currentPage - 1);
                });
            }

            if (currentPage < totalPages) {
                option(Tr.t(session, "rtv.next_page"), (p, s) -> {
                    new RtvMenu(rtvService, mapRatingService).send(p, currentPage + 1);
                });
            }
        }
    }
}
