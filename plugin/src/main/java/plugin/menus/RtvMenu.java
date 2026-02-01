package plugin.menus;

import arc.struct.Seq;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import plugin.handler.MapRating;
import plugin.handler.VoteHandler;
import plugin.type.Session;

public class RtvMenu extends PluginMenu<Integer> {
    private static final int MAPS_PER_PAGE = 7;

    @Override
    public void build(Session session, Integer page) {
        Seq<Map> maps = new Seq<>(VoteHandler.getMaps());

        maps.sort((a, b) -> Float.compare(MapRating.getAvg(b), MapRating.getAvg(a)));

        int totalPages = (int) Math.ceil((double) maps.size / MAPS_PER_PAGE);

        if (totalPages == 0) {
            totalPages = 1;
        }

        int currentPage = Math.max(1, Math.min(page, totalPages));

        this.title = "Available Maps";
        this.description = "Page " + currentPage + " / " + totalPages + "\nClick a map to vote for it.";

        int start = (currentPage - 1) * MAPS_PER_PAGE;
        int end = Math.min(start + MAPS_PER_PAGE, maps.size);

        for (int i = start; i < end; i++) {
            Map map = maps.get(i);
            float rating = MapRating.getAvg(map);
            String ratingColor = MapRating.avgScoreColor(rating);

            String voted = VoteHandler.isVoted(session.player, map.file.nameWithoutExtension()) ? "[accent]Voted" : "";
            String text = String.format("%s%s%.2f [gold]%c [white]%s", voted, ratingColor, rating, Iconc.star,
                    map.name());

            option(text, (p, s) -> {
                VoteHandler.handleVote(session.player, map);
            });
            row();
        }

        boolean hasPrev = currentPage > 1;
        boolean hasNext = currentPage < totalPages;

        if (hasPrev || hasNext) {
            if (hasPrev) {
                option("<< Previous", (p, s) -> {
                    new RtvMenu().send(p, currentPage - 1);
                });
            }

            if (hasNext) {
                option("Next >>", (p, s) -> {
                    new RtvMenu().send(p, currentPage + 1);
                });
            }
            row();
        }

        text("[scarlet]Close");
    }
}
