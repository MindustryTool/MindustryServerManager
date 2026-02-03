package plugin.menus;

import plugin.handler.SessionHandler;
import plugin.type.Session;
import plugin.utils.AdminUtils;
import plugin.handler.ApiGateway;

public class GriefMenu extends PluginMenu<Session> {
    @Override
    public void build(Session session, Session target) {
        this.title = ApiGateway.translate(session.locale, "@Grief Report");

        if (target == null) {
            SessionHandler.each(t -> t != session, t -> {
                option(t.player.name, (_p, s) -> new GriefMenu().send(session, t));
                row();
            });

            text(ApiGateway.translate(session.locale, "[red]", "@Close"));
        } else {
            option(ApiGateway.translate(session.locale, "@Report"),
                    (p, s) -> AdminUtils.reportGrief(session, target));
            row();
            text(ApiGateway.translate(session.locale, "@Cancel"));
        }
    }
}
