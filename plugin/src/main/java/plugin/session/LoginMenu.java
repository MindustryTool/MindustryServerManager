package plugin.session;

import mindustry.gen.Call;
import plugin.core.Registry;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;

public class LoginMenu extends PluginMenu<Void> {

    @Override
    protected void build(Session session, Void state) {
        this.title = Tr.t(session, "login.menu_title");
        this.description = Tr.t(session, "login.menu_desc");

        option(Tr.t(session, "login.btn_open"), (s, st) -> {
            var loginService = Registry.get(LoginService.class);
            var link = loginService.getOrGenerateLink(s.player);
            if (link != null && link.url != null && !link.url.isEmpty()) {
                Call.openURI(s.player.con, link.url);
                s.player.sendMessage(Tr.t(s, "login.link_opened"));
            } else {
                s.player.sendMessage(Tr.t(s, "login.link_expired"));
            }
        });
        row();

        text(Tr.t(session, "login.btn_cancel"));
    }
}
