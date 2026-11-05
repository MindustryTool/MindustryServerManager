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

        ctx.label("[accent]Leaderboard: [gold]" + state.mapName());
        ctx.row();

        List<MapRecord> records = state.records();
        if (records == null || records.isEmpty()) {
            ctx.label("[lightgray]" + Tr.t(locale, "rank.no_records_menu"));
            ctx.row();
            ctx.closeButton("[gray]Close");
            return;
        }

        ctx.pane(MenuContext.SAFE_WIDTH, 300f, paneTable -> {
            for (int i = 0; i < records.size(); i++) {
                MapRecord record = records.get(i);
                int rank = i + 1;
                String time = TimeUtils.toString(Duration.ofMillis(record.durationMs()));
                List<String> names = record.players().stream().map(MapRecordPlayer::name).toList();
                String playersStr = names.isEmpty() ? "-" : String.join(", ", names);

                String rankBadge = rank == 1 ? "[gold]#1 " : rank == 2 ? "[silver]#2 " : rank == 3 ? "[bronze]#3 " : "[lightgray]#" + rank + " ";
                TableBuilder row = UiBuilder.table().pad(4f);

                row.add(UiBuilder.label(rankBadge + "[green]" + time).align("left"));
                row.add(UiBuilder.label("[lightgray]" + playersStr).align("right"));
                row.row();

                paneTable.add(row.growX().pad(2f)).row();
            }
        });

        ctx.row();
        ctx.closeButton("[gray]Close");
    }
}
