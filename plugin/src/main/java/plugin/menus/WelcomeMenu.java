package plugin.menus;

import arc.struct.Seq;
import mindustry.gen.Call;
import mindustry.gen.Iconc;
import plugin.Cfg;
import plugin.core.Registry;
import plugin.service.ApiGateway;
import plugin.type.Session;

public class WelcomeMenu extends PluginMenu<Void> {

    public WelcomeMenu() {
    }

    @Override
    public void build(Session session, Void state) {
        this.title = "MindustryTool";

        Seq<String> translated = Registry.get(ApiGateway.class).translate(
                Seq.with("Rules", "Website", "Discord", "Close"),
                session.locale);

        option(Iconc.book + "[green]" + translated.get(0), (p, s) -> Call.openURI(p.player.con, Cfg.RULE_URL));
        row();

        option(Iconc.link + "[green]" + translated.get(1),
                (p, s) -> Call.openURI(p.player.con, Cfg.MINDUSTRY_TOOL_URL));
        row();

        option(Iconc.discord + "[blue]" + translated.get(2),
                (p, s) -> Call.openURI(p.player.con, Cfg.DISCORD_INVITE_URL));
        row();

        text(Iconc.cancel + "[red]" + translated.get(3));
    }
}
