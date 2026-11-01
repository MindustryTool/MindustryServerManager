package plugin.dailytask;

import java.util.List;

public class DefaultTaskProvider implements GamemodeTaskProvider {
    @Override
    public boolean supports() {
        return true;
    }

    @Override
    public List<DailyTask> getTasks() {
        return List.of(
            new DailyTask("gen_waves", "task.gen_waves_title", "task.gen_waves_desc", 10, 250, DailyTask.TaskType.SURVIVE_WAVES),
            new DailyTask("gen_kills", "task.gen_kills_title", "task.gen_kills_desc", 40, 200, DailyTask.TaskType.KILL_UNITS),
            new DailyTask("gen_blocks", "task.gen_blocks_title", "task.gen_blocks_desc", 25, 150, DailyTask.TaskType.BUILD_BLOCKS)
        );
    }
}
