package plugin.grief;

import plugin.menus.PluginMenu;

import plugin.core.Registry;
import plugin.utils.Tr;
import plugin.session.SessionService;
import plugin.session.Session;

public class GriefMenu extends PluginMenu<Session> {

    public GriefMenu() {
    }

    @Override
    public void build(Session session, Session target) {
        this.title = Tr.t(session.locale, "grief.report_title");

        if (target == null) {
            Registry.get(SessionService.class).each(t -> t != session, t -> {
                option(t.player.name, (_p, s) -> new GriefMenu().send(session, t));
                row();
            });

            text(Tr.t(session.locale, "grief.close"));
        } else {
            option(Tr.t(session.locale, "grief.report"),
                    (p, s) -> Registry.get(AdminService.class).reportGrief(session, target));
            row();
            text(Tr.t(session.locale, "grief.cancel"));
        }
    }
}
