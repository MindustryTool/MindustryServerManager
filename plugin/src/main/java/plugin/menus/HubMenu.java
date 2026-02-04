package plugin.menus;

import mindustry.gen.Call;
import plugin.Config;
import plugin.type.Session;
import plugin.handler.I18n;

public class HubMenu extends PluginMenu<String> {
    @Override
    public void build(Session session, String loginLink) {
        this.title = I18n.t(session.locale, "@Servers");

        if (loginLink != null && !loginLink.isEmpty()) {
            option(I18n.t(session.locale, "[green]", "@Login via", " MindustryTool"),
                    (p, s) -> Call.openURI(p.player.con, s));
            row();
        }

        option(I18n.t(session.locale, "[green]", "@Rules"),
                (p, s) -> Call.openURI(p.player.con, Config.RULE_URL));
        row();

        option(I18n.t(session.locale, "[green]", "@Website"),
                (p, s) -> Call.openURI(p.player.con, Config.MINDUSTRY_TOOL_URL));
        row();

        option(I18n.t(session.locale, "[blue]", "@Discord"),
                (p, s) -> Call.openURI(p.player.con, Config.DISCORD_INVITE_URL));
        row();

        text(I18n.t(session.locale, "[red]", "@Close"));
    }
}
