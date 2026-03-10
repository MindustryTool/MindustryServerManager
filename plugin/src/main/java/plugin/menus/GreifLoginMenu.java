package plugin.menus;

import mindustry.gen.Call;
import plugin.service.I18n;
import plugin.type.Session;

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
