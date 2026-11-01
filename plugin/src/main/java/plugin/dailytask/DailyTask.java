package plugin.dailytask;

import lombok.Getter;
import plugin.session.Session;
import plugin.utils.Tr;

@Getter
public class DailyTask {
    private final String id;
    private final String titleKey;
    private final String descriptionKey;
    private final int goal;
    private final long rewardCoins;
    private final TaskType type;

    public DailyTask(String id, String titleKey, String descriptionKey, int goal, long rewardCoins, TaskType type) {
        this.id = id;
        this.titleKey = titleKey;
        this.descriptionKey = descriptionKey;
        this.goal = goal;
        this.rewardCoins = rewardCoins;
        this.type = type;
    }

    public String getTitle(Session session) {
        return Tr.t(session, titleKey);
    }

    public String getDescription(Session session) {
        return Tr.t(session, descriptionKey);
    }

    public enum TaskType {
        SURVIVE_WAVES,
        KILL_UNITS,
        BUILD_BLOCKS
    }
}
