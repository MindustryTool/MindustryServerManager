package plugin.maprecord;

import java.util.List;

import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.Gamemode;
import plugin.gamemode.GamemodeCondition;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@ConditionOn(value = GamemodeCondition.class, args = {"attack", "ziger", "flood", "survival", "TowerDefense"})
@RequiredArgsConstructor
public class MapRecordCommands {
    private final MapRecordRepository repository;

    @ClientCommand(name = "records", description = "View top 10 map records", admin = false)
    public void records(Session session) {
        if (Vars.state.map == null || Vars.state.map.file == null) {
            session.player.sendMessage(Tr.t(session.locale, "rank.no_record"));
            return;
        }

        String mapName = Vars.state.map.file.nameWithoutExtension();
        String currentMode = Gamemode.current();
        List<MapRecord> records = repository.getTopRecords(mapName, currentMode, 10);

        MapRecordMenuState state = new MapRecordMenuState(Vars.state.map.name(), currentMode, records);
        new MapRecordsMenu().send(session, state);
    }
}
