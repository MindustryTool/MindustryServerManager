package plugin.admin;

import java.util.List;
import mindustry.Vars;
import plugin.Cfg;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Param;
import plugin.dailytask.DailyTask;
import plugin.dailytask.DailyTaskService;
import plugin.money.MoneyService;
import plugin.session.Session;
import plugin.session.SessionService;
import plugin.utils.Tr;

@Component
@ConditionOn(Cfg.OnOfficial.class)
public class EconomyAdminCommands {
    private final MoneyService moneyService;
    private final DailyTaskService dailyTaskService;
    private final SessionService sessionService;

    public EconomyAdminCommands(MoneyService moneyService, DailyTaskService dailyTaskService, SessionService sessionService) {
        this.moneyService = moneyService;
        this.dailyTaskService = dailyTaskService;
        this.sessionService = sessionService;
    }

    private Session findTarget(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        return sessionService.find(s -> s.player != null && (
                target.equalsIgnoreCase(s.player.plainName()) ||
                target.equalsIgnoreCase(s.player.name) ||
                target.equals(s.player.uuid())
        ));
    }

    private void send(Session session, String message) {
        if (session.player != null && Vars.net != null) {
            session.player.sendMessage(message);
        }
    }

    @ClientCommand(name = "admin-money", description = "Manage player economy: /admin-money <inspect|give|take|set> <player> [amount]", admin = true)
    public void adminMoney(Session session,
                           @Param(name = "action") String action,
                           @Param(name = "player") String playerName,
                           @Param(name = "amount", required = false) String amountStr) {
        if (action == null || playerName == null) {
            send(session, Tr.t(session, "money.admin_usage"));
            return;
        }

        Session target = findTarget(playerName);
        if (target == null) {
            send(session, Tr.t(session, "money.admin_invalid_target"));
            return;
        }

        String act = action.trim().toLowerCase();
        if ("inspect".equals(act)) {
            send(session, Tr.t(session, "money.admin_inspect_title", "name", target.player.name));
            send(session, Tr.t(session, "money.admin_inspect_balance", "coins", target.getData().money));
            send(session, Tr.t(session, "money.admin_inspect_core", "tier", target.getData().coreUpgradeLevel));
            List<DailyTask> tasks = dailyTaskService.getActiveTasks();
            long completed = tasks.stream().filter(t -> dailyTaskService.isCompleted(target, t)).count();
            send(session, Tr.t(session, "money.admin_inspect_tasks", "completed", completed, "total", tasks.size()));
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(amountStr);
            if (amount < 0) {
                send(session, Tr.t(session, "money.admin_invalid_amount"));
                return;
            }
        } catch (Exception e) {
            send(session, Tr.t(session, "money.admin_invalid_amount"));
            return;
        }

        switch (act) {
            case "give" -> {
                moneyService.deposit(target, amount);
                send(session, Tr.t(session, "money.admin_give_success",
                        "coins", amount, "name", target.player.name, "balance", target.getData().money));
            }
            case "take" -> {
                moneyService.withdraw(target, amount);
                send(session, Tr.t(session, "money.admin_take_success",
                        "coins", amount, "name", target.player.name, "balance", target.getData().money));
            }
            case "set" -> {
                moneyService.setBalance(target, amount);
                send(session, Tr.t(session, "money.admin_set_success",
                        "name", target.player.name, "balance", target.getData().money));
            }
            default -> send(session, Tr.t(session, "money.admin_usage"));
        }
    }

    @ClientCommand(name = "admin-tasks", description = "Manage daily tasks: /admin-tasks reset <player>", admin = true)
    public void adminTasks(Session session,
                           @Param(name = "action") String action,
                           @Param(name = "player") String playerName) {
        if (!"reset".equalsIgnoreCase(action) || playerName == null) {
            send(session, Tr.t(session, "task.admin_reset_usage"));
            return;
        }

        Session target = findTarget(playerName);
        if (target == null) {
            send(session, Tr.t(session, "money.admin_invalid_target"));
            return;
        }

        dailyTaskService.resetTasks(target);
        send(session, Tr.t(session, "task.admin_reset_success", "name", target.player.name));
    }
}
