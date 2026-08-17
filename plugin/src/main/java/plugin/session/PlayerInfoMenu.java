package plugin.session;

import plugin.hub.ServerListMenu;

import plugin.menus.PluginMenu;

import plugin.core.Registry;
import plugin.utils.Tr;

public class PlayerInfoMenu extends PluginMenu<Session> {

    public PlayerInfoMenu() {
    }

    @Override
    public void build(Session session, Session caller) {
        this.title = Tr.t(session.locale, "session.players_title");

        Registry.get(SessionService.class).each(p -> {
            option(p.player.name, (t, s) -> {
                caller.player.sendMessage(SessionUtils.getInfoString(p, p.getData()));
            });
            row();
        });

        option(Tr.t(session.locale, "session.close"),
                (p, s) -> new ServerListMenu().send(session, 0));
    }
}
