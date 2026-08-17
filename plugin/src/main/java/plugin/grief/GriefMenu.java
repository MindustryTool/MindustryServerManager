package plugin.grief;

import plugin.menus.PluginMenu;

import plugin.core.Registry;
import plugin.utils.I18n;
import plugin.session.SessionService;
import plugin.session.Session;

public class GriefMenu extends PluginMenu<Session> {

    public GriefMenu() {
    }

    @Override
    public void build(Session session, Session target) {
        this.title = I18n.t(session.locale, "@Grief Report");

        if (target == null) {
            Registry.get(SessionService.class).each(t -> t != session, t -> {
                option(t.player.name, (_p, s) -> new GriefMenu().send(session, t));
                row();
            });

            text(I18n.t(session.locale, "[red]", "@Close"));
        } else {
            option(I18n.t(session.locale, "@Report"),
                    (p, s) -> Registry.get(AdminService.class).reportGrief(session, target));
            row();
            text(I18n.t(session.locale, "@Cancel"));
        }
    }
}
