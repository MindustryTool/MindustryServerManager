package plugin.maprecord;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.TimeUtils;
import plugin.utils.Tr;

@Component
public class MapRecordsMenu extends ServerMenu<MapRecordMenuState> {

    @Override
    public void build(MenuContext<MapRecordMenuState> ctx) {
        MapRecordMenuState state = ctx.getState();
        if (state == null) {
            return;
        }

        Locale locale = ctx.getSession().locale;
        this.title = Tr.t(locale, "rank.menu_title");

        ctx.label(Tr.t(ctx.getSession(), "rank.leaderboard", "map", state.mapName())).width(MenuContext.SAFE_WIDTH).padBottom(4f);
        ctx.row();

        List<MapRecord> records = state.records();
        if (records == null || records.isEmpty()) {
            ctx.label("[lightgray]" + Tr.t(locale, "rank.no_records_menu")).width(MenuContext.SAFE_WIDTH).pad(8f);
            return;
        }

        ctx.pane(MenuContext.SAFE_WIDTH, MenuContext.MOBILE_MAX_PANE_HEIGHT, paneTable -> {
            for (int i = 0; i < records.size(); i++) {
                MapRecord record = records.get(i);
                int rank = i + 1;
                String time = TimeUtils.toString(Duration.ofMillis(record.durationMs()));
                List<String> names = record.players().stream().map(MapRecordPlayer::name).toList();
                String playersStr = names.isEmpty() ? "-" : String.join(", ", names);

                String rankBadge = rank == 1 ? "[gold]#1" : rank == 2 ? "[#C0C0C0]#2" : rank == 3 ? "[#CD7F32]#3" : "[lightgray]#" + rank;
                TableBuilder card = UiBuilder.table().background("pane").margin(10f).align("left");
                card.width(MenuContext.SAFE_CARD_WIDTH);

                card.add(UiBuilder.label(rankBadge).align("left").growX());
                card.add(UiBuilder.label("[green]" + time).align("right").padLeft(6f));
                card.row();
                card.add(UiBuilder.label("[lightgray]" + playersStr).wrap().align("left").colspan(2).growX().padTop(2f));

                paneTable.add(card.growX().pad(2f)).row();
            }
        });
    }
}
