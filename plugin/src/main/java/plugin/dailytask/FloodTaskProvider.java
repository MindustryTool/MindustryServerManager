package plugin.dailytask;

import java.util.List;
import plugin.gamemode.Gamemode;

public class FloodTaskProvider implements GamemodeTaskProvider {
    @Override
    public boolean supports() {
        return Gamemode.active("Flood");
    }

    @Override
    public List<DailyTask> getTasks() {
        return List.of(
            new DailyTask("flood_waves", "task.flood_waves_title", "task.flood_waves_desc", 10, 300, DailyTask.TaskType.SURVIVE_WAVES),
            new DailyTask("flood_blocks", "task.flood_blocks_title", "task.flood_blocks_desc", 30, 250, DailyTask.TaskType.BUILD_BLOCKS),
            new DailyTask("flood_kills", "task.flood_kills_title", "task.flood_kills_desc", 30, 200, DailyTask.TaskType.KILL_UNITS)
        );
    }
}
