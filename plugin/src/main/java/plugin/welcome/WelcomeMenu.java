package plugin.welcome;

import plugin.menus.PluginMenu;

import mindustry.gen.Call;
import mindustry.gen.Iconc;
import plugin.Cfg;
import plugin.session.Session;
import plugin.utils.Tr;

public class WelcomeMenu extends PluginMenu<Void> {

    public WelcomeMenu() {
    }

    @Override
    public void build(Session session, Void state) {
        this.title = "MindustryTool";

        option(Iconc.book + "[green]" + Tr.t(session, "welcome.rules"),
                (p, s) -> Call.openURI(p.player.con, Cfg.RULE_URL));
        row();

        option(Iconc.link + "[green]" + Tr.t(session, "welcome.website"),
                (p, s) -> Call.openURI(p.player.con, Cfg.MINDUSTRY_TOOL_URL));
        row();

        option(Iconc.discord + "[blue]" + Tr.t(session, "welcome.discord"),
                (p, s) -> Call.openURI(p.player.con, Cfg.DISCORD_INVITE_URL));
        row();

        text(Iconc.cancel + "[red]" + Tr.t(session, "welcome.close"));
    }
}
