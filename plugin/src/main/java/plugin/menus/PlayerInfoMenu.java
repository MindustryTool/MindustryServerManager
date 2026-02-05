package plugin.menus;

import plugin.handler.SessionHandler;
import plugin.type.Session;
import plugin.handler.I18n;

import plugin.Registry;

public class PlayerInfoMenu extends PluginMenu<Session> {

    public PlayerInfoMenu() {
    }

    @Override
    public void build(Session Session, Session caller) {
        this.title = I18n.t(session.locale, "@Servers");

        Registry.get(SessionHandler.class).each(p -> {
            option(p.player.name, (t, s) -> {
                caller.player.sendMessage(p.info());
            });
            row();
        });

        option(I18n.t(session.locale, "[red]", "@Close"),
                (p, s) -> new ServerListMenu().send(session, 0));
    }
}
