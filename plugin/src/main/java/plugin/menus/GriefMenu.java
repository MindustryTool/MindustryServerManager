package plugin.menus;

import plugin.handler.SessionHandler;
import plugin.type.Session;
import plugin.utils.AdminUtils;

public class GriefMenu extends PluginMenu<Session> {
    @Override
    public void build(Session session, Session target) {
        this.title = "Grief Report";

        if (target == null) {
            SessionHandler.each(t -> {
                option(t.player.name, (_p, s) -> new GriefMenu().send(session, t));
                row();
            });

            text("[red]Close");
        } else {
            option("Report", (p, s) -> AdminUtils.reportGrief(session, target));
            row();
            text("Cancel");
        }
    }
}
