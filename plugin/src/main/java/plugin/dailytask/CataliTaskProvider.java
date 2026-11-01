package plugin.dailytask;

import java.util.List;
import plugin.gamemode.Gamemode;

public class CataliTaskProvider implements GamemodeTaskProvider {
    @Override
    public boolean supports() {
        return Gamemode.active("Catali");
    }

    @Override
    public List<DailyTask> getTasks() {
        return List.of(
            new DailyTask("catali_kills", "task.catali_kills_title", "task.catali_kills_desc", 30, 300, DailyTask.TaskType.KILL_UNITS),
            new DailyTask("catali_blocks", "task.catali_blocks_title", "task.catali_blocks_desc", 20, 250, DailyTask.TaskType.BUILD_BLOCKS),
            new DailyTask("catali_waves", "task.catali_waves_title", "task.catali_waves_desc", 5, 200, DailyTask.TaskType.SURVIVE_WAVES)
        );
    }
}
