package plugin.dailytask;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class TaskMenu extends ServerMenu<Integer> {

    private final DailyTaskService dailyTaskService;

    @Override
    public void build(MenuContext<Integer> ctx) {
        if (dailyTaskService == null) {
            ctx.label(Tr.t(ctx.getSession(), "task.unavailable"));
            ctx.row();
            ctx.closeButton(Tr.t(ctx.getSession(), "task.close"));
            return;
        }

        dailyTaskService.checkRollover(ctx.getSession());
        this.title = Tr.t(ctx.getSession(), "task.menu_title", "date", LocalDate.now().toString());

        ctx.label("[accent]" + Tr.t(ctx.getSession(), "task.menu_description"));
        ctx.row();

        List<DailyTask> tasks = dailyTaskService.getActiveTasks();

        ctx.pane(MenuContext.SAFE_WIDTH, 320f, paneTable -> {
            for (DailyTask task : tasks) {
                int progress = dailyTaskService.getProgress(ctx.getSession(), task.getId());
                boolean done = progress >= task.getGoal();

                TableBuilder card = UiBuilder.table().pad(6f);
                String statusIcon = done ? "[green]" + Iconc.ok + " " : "[yellow]⏳ ";
                String titleText = statusIcon + "[white]" + task.getTitle(ctx.getSession());
                String rewardBadge = "[gold]+" + task.getRewardCoins() + " Coins";

                card.add(UiBuilder.label(titleText).align("left"));
                card.add(UiBuilder.label(rewardBadge).align("right"));
                card.row();

                card.add(UiBuilder.label("[lightgray]" + task.getDescription(ctx.getSession())).wrap().align("left").colspan(2));
                card.row();

                String progressText = String.format("[accent]Progress: [white]%d / %d", Math.min(progress, task.getGoal()), task.getGoal());
                card.add(UiBuilder.label(progressText).align("left").colspan(2));
                card.row();

                paneTable.add(card.growX().pad(4f)).row();
            }
        });

        ctx.row();
        ctx.closeButton(Tr.t(ctx.getSession(), "task.close"));
    }
}
