package plugin.menus;

import plugin.handler.SessionHandler;
import plugin.type.Session;
import plugin.handler.ApiGateway;

public class PlayerInfoMenu extends PluginMenu<Session> {
    @Override
    public void build(Session Session, Session caller) {
        this.title = ApiGateway.translate(session.locale, "@Servers");

        SessionHandler.each(p -> {
            option(p.player.name, (t, s) -> {
                caller.player.sendMessage(p.info());
            });
            row();
        });

        option(ApiGateway.translate(session.locale, "[red]", "@Close"),
                (p, s) -> new ServerListMenu().send(session, 0));
    }
}
