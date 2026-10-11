package plugin.maprecord;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import mindustry.gen.Call;
import plugin.menus.PluginMenu;
import plugin.session.Session;
import plugin.utils.TimeUtils;
import plugin.utils.Tr;

public class MapRecordsMenu extends PluginMenu<MapRecordMenuState> {

    @Override
    public void build(Session session, MapRecordMenuState state) {
        Locale locale = session.locale;
        this.title = Tr.t(locale, "rank.menu_title");
        this.description = Tr.t(locale, "rank.menu_desc", "map", state.mapName());

        List<MapRecord> records = state.records();
        if (records == null || records.isEmpty()) {
            option(Tr.t(locale, "rank.no_records_menu"), (s, st) -> {
                Call.hideFollowUpMenu(s.player.con, this.getMenuId());
            });
            row();
            return;
        }

        for (int i = 0; i < records.size(); i++) {
            MapRecord record = records.get(i);
            int rank = i + 1;
            String time = TimeUtils.toString(Duration.ofMillis(record.durationMs()));
            List<String> names = record.players().stream().map(MapRecordPlayer::name).toList();
            String playersStr = names.isEmpty() ? "-" : String.join(", ", names);

            String text = Tr.t(locale, "rank.menu_entry",
                    "rank", String.valueOf(rank),
                    "time", time,
                    "players", playersStr);

            option(text, (s, st) -> {
                Call.hideFollowUpMenu(s.player.con, this.getMenuId());
            });
            row();
        }
    }
}
