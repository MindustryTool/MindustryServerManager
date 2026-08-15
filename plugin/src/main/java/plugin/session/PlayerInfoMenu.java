package plugin.session;

import plugin.hub.ServerListMenu;

import plugin.menus.PluginMenu;

import plugin.core.Registry;
import plugin.utils.I18n;

public class PlayerInfoMenu extends PluginMenu<Session> {

    public PlayerInfoMenu() {
    }

    @Override
    public void build(Session session, Session caller) {
        this.title = I18n.t(session.locale, "@Servers");

        Registry.get(SessionHandler.class).each(p -> {
            option(p.player.name, (t, s) -> {
                caller.player.sendMessage(SessionUtils.getInfoString(p, p.getData()));
            });
            row();
        });

        option(I18n.t(session.locale, "[red]", "@Close"),
                (p, s) -> new ServerListMenu().send(session, 0));
    }
}
