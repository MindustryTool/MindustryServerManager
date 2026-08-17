package plugin.grief;

import plugin.menus.PluginMenu;

import mindustry.gen.Call;
import plugin.utils.Tr;
import plugin.session.Session;

public class GreifLoginMenu extends PluginMenu<String> {

    @Override
    protected void build(Session session, String url) {
        description = Tr.t(session, "grief.login_prompt");

        text(Tr.t(session, "grief.cancel"));
        option(Tr.t(session, "grief.login"), (p, s) -> {
            Call.openURI(session.player.con, url);
        });
    }

}
