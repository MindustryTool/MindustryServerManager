package plugin.grief;

import lombok.RequiredArgsConstructor;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.session.Session;

@Component
@RequiredArgsConstructor
public class GriefCommands {

    private final AdminService adminService;

    @ClientCommand(name = "grief", description = "Report a player", admin = false)
    public void grief(Session session) {
        if (adminService.isGriefVoting()) {
            adminService.voteGrief(session);
            return;
        }

        new GriefMenu().send(session, null);
    }
}