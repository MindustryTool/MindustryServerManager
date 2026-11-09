package plugin.dailytask;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.Cfg.OnOfficial;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(OnOfficial.class)
public class TaskMenu extends ServerMenu<Integer> {

    private final DailyTaskService dailyTaskService;

    @Override
    public void build(MenuContext<Integer> ctx) {
        dailyTaskService.checkRollover(ctx.getSession());
        this.title = Tr.t(ctx.getSession(), "task.menu_title", "date", LocalDate.now().toString());

        ctx.label("[accent]" + Tr.t(ctx.getSession(), "task.menu_description"));
        ctx.row();

        List<DailyTask> tasks = dailyTaskService.getActiveTasks();

        ctx.pane(320f, paneTable -> {
            for (DailyTask task : tasks) {
                int progress = dailyTaskService.getProgress(ctx.getSession(), task.getId());
                boolean done = progress >= task.getGoal();

                TableBuilder card = UiBuilder.table().align("left");
                card.width(ctx.safeCardWidth());
                String statusIcon = done ? "[green]" + Iconc.ok + " " : "[yellow]⏳ ";
                String titleText = statusIcon + "[white]" + task.getTitle(ctx.getSession());
                String rewardBadge = Tr.t(ctx.getSession(), "task.reward_badge", "coins", task.getRewardCoins());

                card.add(UiBuilder.label(titleText).wrap().align("left").growX().padLeft(4f));
                card.add(UiBuilder.label(rewardBadge).align("right").padLeft(4f));
                card.row();

                card.add(UiBuilder.label("[lightgray]" + task.getDescription(ctx.getSession())).wrap().align("left")
                        .colspan(2).growX().padLeft(4f));
                card.row();

                String progressText = Tr.t(ctx.getSession(), "task.progress", "current",
                        Math.min(progress, task.getGoal()), "target", task.getGoal());
                card.add(UiBuilder.label(progressText).align("left").colspan(2).padLeft(4f));
                card.row();

                paneTable.add(card.growX().pad(4f)).row();
            }
        });
    }
}
