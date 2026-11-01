package plugin.dailytask;

import java.util.List;

public interface GamemodeTaskProvider {
    boolean supports();

    List<DailyTask> getTasks();
}
