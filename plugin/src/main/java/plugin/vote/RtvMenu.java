package plugin.vote;

import arc.files.Fi;
import arc.struct.Seq;
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
                        Vars.netServer.sendTexture(player.con, "net-map-" + map.file.nameWithoutExtension(), bytes);
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

        this.title = Tr.t(ctx.getSession(), "rtv.available_maps");

        ctx.label(Tr.t(ctx.getSession(), "rtv.select_map")).padBottom(4f);
        ctx.row();

        TableBuilder grid = UiBuilder.table().align("top").align("left");

        if (entries.isEmpty()) {
            grid.add(UiBuilder.label("[lightgray]" + Tr.t(ctx.getSession(), "maprating.no_more_maps"))
                    .wrap()
                    .align("left")
                    .growX()
                    .pad(16f));
        } else {
            int cols = ctx.isMobile() ? 2 : 3;
            int index = 0;
            for (RtvMapEntry entry : entries) {
                Map map = entry.map();
                MapRatingStat stats = entry.stat();
                String ratingColor = mapRatingService.avgScoreColor(stats.avgScore);
                boolean isVoted = rtvService.isVoted(ctx.getSession().player, map.file.nameWithoutExtension());
                String mapId = map.file.nameWithoutExtension();

                TableBuilder card = UiBuilder.table().background("pane").margin(10f).align("top").align("center");
                card.width(148f).pad(4f);

                card.add(UiBuilder.image("net-map-" + mapId)
                        .placeholder("nomap")
                        .scaling(Scaling.fit)
                        .width(128f)
                        .height(80f)
                        .align("center"))
                    .row();

                String nameText = (map.name() != null && !map.name().isEmpty()) ? map.name() : mapId;
                card.add(UiBuilder.label("[white]" + nameText)
                        .wrap()
                        .align("center")
                        .labelAlign("center")
                        .width(128f)
                        .padTop(4f))
                    .row();

                String ratingText = String.format("%s%.2f [gold]%c [gray](%d)",
                    ratingColor, stats.avgScore, Iconc.star, stats.totalVotes);
                card.add(UiBuilder.label(ratingText)
                        .align("center")
                        .labelAlign("center")
                        .width(128f)
                        .padTop(2f))
                    .row();

                String voteAct = ctx.registerAction((sess, st, res) -> rtvService.handleVote(sess.player, map));
                String voteBtnText = isVoted
                    ? "[scarlet]" + Tr.t(ctx.getSession(), "rtv.cancel_vote")
                    : "[accent]" + Tr.t(ctx.getSession(), "rtv.vote_map");

                card.add(UiBuilder.button(voteBtnText)
                        .clicked(voteAct)
                        .width(128f)
                        .height(34f)
                        .align("center")
                        .padTop(4f));

                grid.add(card);
                if (++index % cols == 0) {
                    grid.row();
                }
            }
        }

        PaneBuilder pane = UiBuilder.pane().add(grid).grow();
        ctx.add(pane);
    }
}
