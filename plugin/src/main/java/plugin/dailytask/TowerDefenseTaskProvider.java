package plugin.dailytask;

import java.util.List;
import plugin.gamemode.Gamemode;

public class TowerDefenseTaskProvider implements GamemodeTaskProvider {
    @Override
    public boolean supports() {
        return Gamemode.active("TowerDefense");
    }

    @Override
    public List<DailyTask> getTasks() {
        return List.of(
            new DailyTask("td_waves", "task.td_waves_title", "task.td_waves_desc", 15, 300, DailyTask.TaskType.SURVIVE_WAVES),
            new DailyTask("td_kills", "task.td_kills_title", "task.td_kills_desc", 50, 250, DailyTask.TaskType.KILL_UNITS),
            new DailyTask("td_turrets", "task.td_turrets_title", "task.td_turrets_desc", 10, 200, DailyTask.TaskType.BUILD_BLOCKS)
        );
    }
}
