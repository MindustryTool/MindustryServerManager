package plugin.maprecord;

import java.util.List;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Param;
import plugin.gamemode.Gamemode;
import plugin.gamemode.GamemodeCondition;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@ConditionOn(value = GamemodeCondition.class, args = {"attack", "ziger", "flood", "survival", "TowerDefense"})
@RequiredArgsConstructor
public class MapRecordCommands {
    private final MapRecordRepository repository;
    private final MapRecordsMenu mapRecordsMenu;
    private final MapRecordService recordService;

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
        mapRecordsMenu.send(session, state);
    }

    @ClientCommand(name = "records-clear", description = "Clear map records: /records-clear <all|current>", admin = true)
    public void recordsClear(Session session, @Param(name = "target") String target) {
        if ("all".equalsIgnoreCase(target)) {
            repository.clearAll();
            recordService.resetCachedRecord();
            session.player.sendMessage("[green]All map records cleared.");
        } else if ("current".equalsIgnoreCase(target)) {
            if (Vars.state.map == null || Vars.state.map.file == null) {
                session.player.sendMessage("[scarlet]No map currently loaded.");
                return;
            }
            String mapName = Vars.state.map.file.nameWithoutExtension();
            repository.clearByMap(mapName);
            recordService.resetCachedRecord();
            session.player.sendMessage("[green]Map records for current map cleared.");
        } else {
            session.player.sendMessage("[scarlet]Usage: /records-clear <all|current>");
        }
    }
}
