package plugin.commands.client;

import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.menus.GriefMenu;
import plugin.service.AdminService;
import plugin.type.Session;

@Component
public class GriefCommand extends PluginClientCommand {

    private final AdminService adminService;

    public GriefCommand(AdminService adminService) {
        setName("grief");
        setDescription("Report a player");
        setAdmin(false);

        this.adminService = adminService;
    }

    @Override
    public void handle(Session session) {
        if (adminService.isGriefVoting()) {
            adminService.voteGrief(session);
            return;
        }

        new GriefMenu().send(session, null);
    }
}
