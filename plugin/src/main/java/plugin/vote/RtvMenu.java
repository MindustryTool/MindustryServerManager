package plugin.vote;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Scaling;
import mindustry.Vars;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.maps.Map;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.PaneBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.maprating.MapRatingService;
import plugin.maprating.MapRatingStat;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
public class RtvMenu extends ServerMenu<Integer> {

    public record RtvMapEntry(Map map, MapRatingStat stat, float sortScore) {
    }

    private final RtvService rtvService;
    private final MapRatingService mapRatingService;

    public RtvMenu(RtvService rtvService, MapRatingService mapRatingService) {
        this.rtvService = rtvService;
        this.mapRatingService = mapRatingService;
        this.fillScreen = true;
    }

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

    public static Fi findPreRenderedImage(Map map) {
        if (map == null || map.file == null) {
            return null;
        }
        Fi siblingMsavPng = map.file.sibling(map.file.name() + ".png");
        if (siblingMsavPng.exists()) {
            return siblingMsavPng;
        }
        Fi siblingPng = map.file.sibling(map.file.nameWithoutExtension() + ".png");
        if (siblingPng.exists()) {
            return siblingPng;
        }
        if (Vars.mapPreviewDirectory != null) {
            try {
                Fi previewFile = map.previewFile();
                if (previewFile != null && previewFile.exists()) {
                    return previewFile;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static void streamMapTextures(Player player, Seq<Map> maps) {
        if (player == null || player.con == null || Vars.netServer == null) {
            return;
        }
        Tasks.io("stream-map-previews", () -> {
            for (Map map : maps) {
                Fi imageFile = findPreRenderedImage(map);
                if (imageFile != null && imageFile.exists()) {
                    byte[] bytes = imageFile.readBytes();
                    if (bytes.length > 0 && player.con != null && Vars.netServer != null) {
                        Vars.netServer.sendTexture(player.con, "map-" + map.file.nameWithoutExtension(), bytes);
                    }
                }
            }
        });
    }

    @Override
    public void build(MenuContext<Integer> ctx) {
        Seq<RtvMapEntry> entries = prepareSortedEntries(rtvService.getMaps(), mapRatingService);
        Seq<Map> maps = new Seq<>(entries.size);
        for (RtvMapEntry entry : entries) {
            maps.add(entry.map());
        }

        if (ctx.getSession() != null && ctx.getSession().player != null) {
            streamMapTextures(ctx.getSession().player, maps);
        }

        ctx.getRoot().align("top");

        this.title = Tr.t(ctx.getSession(), "rtv.available_maps");

        float cardWidth = 280f;
        float cardHeight = 310f;
        float contentWidth = cardWidth - 20f;
        float spacing = 8f;
        int maxCols = ctx.getResponsiveColumns(cardWidth, spacing);
        float innerWidth = maxCols * cardWidth + Math.max(0, maxCols - 1) * spacing;
        int index = 0;

        Log.info("[RtvMenu-Debug] build() player='@', entries=@, cardWidth=@, spacing=@, maxCols=@, innerWidth=@, fillScreen=@",
                (ctx.getSession() != null && ctx.getSession().player != null) ? ctx.getSession().player.name : "null",
                entries.size, cardWidth, spacing, maxCols, innerWidth, this.fillScreen);

        TableBuilder grid = UiBuilder.table().align("topLeft").width(innerWidth);

        if (entries.isEmpty()) {
            grid.add(UiBuilder.label("[lightgray]" + Tr.t(ctx.getSession(), "maprating.no_more_maps"))
                    .wrap()
                    .align("center")
                    .growX()
                    .pad(16f));
        } else {
            for (RtvMapEntry entry : entries) {
                Map map = entry.map();
                MapRatingStat stats = entry.stat();
                String ratingColor = mapRatingService.avgScoreColor(stats.avgScore);
                boolean isVoted = rtvService.isVoted(ctx.getSession().player, map.file.nameWithoutExtension());
                String mapId = map.file.nameWithoutExtension();

                TableBuilder card = UiBuilder.table()
                        .background("pane")
                        .margin(10f)
                        .align("topLeft")
                        .width(cardWidth)
                        .height(cardHeight)
                        .pad(4f);

                card.add(UiBuilder.image("net-map-" + mapId)
                        .placeholder("nomap")
                        .scaling(Scaling.fit)
                        .width(contentWidth)
                        .height(160f)
                        .align("center"))
                        .row();

                String nameText = (map.name() != null && !map.name().isEmpty()) ? map.name() : mapId;
                card.add(UiBuilder.label("[white]" + nameText)
                        .wrap()
                        .align("left")
                        .labelAlign("left")
                        .width(contentWidth)
                        .padTop(6f))
                        .row();

                String ratingText = String.format("%s%.2f [gold]%c [gray](%d)",
                        ratingColor, stats.avgScore, Iconc.star, stats.totalVotes);
                card.add(UiBuilder.label(ratingText)
                        .align("left")
                        .labelAlign("left")
                        .width(contentWidth)
                        .padTop(2f))
                        .row();

                card.add(UiBuilder.space().growY()).row();

                String voteAct = ctx.registerAction((sess, st, res) -> rtvService.handleVote(sess.player, map));
                String voteBtnText = isVoted
                        ? "[scarlet]" + Tr.t(ctx.getSession(), "rtv.cancel_vote")
                        : "[accent]" + Tr.t(ctx.getSession(), "rtv.vote_map");

                card.add(UiBuilder.button(voteBtnText)
                        .clicked(voteAct)
                        .width(contentWidth)
                        .height(36f)
                        .align("left")
                        .padTop(8f));

                grid.add(card);
                if (++index % maxCols == 0) {
                    grid.row();
                }
            }
        }

        TableBuilder contentWrapper = UiBuilder.table().align("top");
        contentWrapper.add(grid);

        PaneBuilder pane = UiBuilder.pane().add(contentWrapper).grow().align("top");
        ctx.add(pane);
    }
}
