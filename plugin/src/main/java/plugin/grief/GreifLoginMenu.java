package plugin.grief;

import plugin.menus.PluginMenu;

import mindustry.gen.Call;
import plugin.utils.I18n;
import plugin.session.Session;

public class GreifLoginMenu extends PluginMenu<String> {

    @Override
    protected void build(Session session, String url) {
        description = I18n.t(session, "@Login to prove that youre not a griefer");

        text("@Cancel");
        option(I18n.t(session, "@Login"), (p, s) -> {
            Call.openURI(session.player.con, url);
        });
    }

}
