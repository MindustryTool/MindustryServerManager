package plugin.menus;

import plugin.handler.SessionHandler;
import plugin.type.Session;

public class PlayerInfoMenu extends PluginMenu<Session> {
    @Override
    public void build(Session Session, Session caller) {
        this.title = "Servers";

        SessionHandler.each(p -> {
            option(p.player.name, (t, s) -> {
                caller.player.sendMessage(t.info());
            });
            row();
        });

        option("[red]Close", (p, s) -> new ServerListMenu().send(session, 0));
    }
}
