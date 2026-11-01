package plugin.dailytask;

import java.time.LocalDate;
import mindustry.gen.Iconc;
import plugin.core.Registry;
import plugin.menus.PluginMenu;
import plugin.session.Session;
import plugin.utils.Tr;

public class TaskMenu extends PluginMenu<Integer> {

    @Override
    public void build(Session session, Integer state) {
        DailyTaskService service = Registry.get(DailyTaskService.class);
        if (service == null) {
            text(Tr.t(session, "task.unavailable"));
            return;
        }

        service.checkRollover(session);
        this.title = Tr.t(session, "task.menu_title", "date", LocalDate.now().toString());
        this.description = Tr.t(session, "task.menu_description");

        var tasks = service.getActiveTasks();
        for (DailyTask task : tasks) {
            int progress = service.getProgress(session, task.getId());
            boolean done = progress >= task.getGoal();

            String statusPrefix = done ? "[green]" + Iconc.ok + " " : "[yellow]";
            String label = Tr.t(session, "task.status_format",
                    "status", statusPrefix,
                    "title", task.getTitle(session),
                    "current", Math.min(progress, task.getGoal()),
                    "target", task.getGoal(),
                    "coins", task.getRewardCoins());

            text(label);
            row();
            text(Tr.t(session, "task.bullet_format", "description", task.getDescription(session)));
            row();
        }

        row();
        text(Tr.t(session, "task.close"));
    }
}
