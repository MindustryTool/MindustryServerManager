package plugin.menus;

import arc.struct.Seq;
import mindustry.gen.Call;
import mindustry.gen.Iconc;
import plugin.Config;
import plugin.handler.ApiGateway;
import plugin.type.Session;
import plugin.utils.Utils;

public class WelcomeMenu extends PluginMenu<Void> {
    @Override
    public void build(Session session, Void state) {
        this.title = "MindustryTool";

        Seq<String> translated = ApiGateway.translate(Seq.with("Rules", "Website", "Discord", "Close"),
                Utils.parseLocale(session.player.locale()));

        option(Iconc.book + "[green]" + translated.get(0), (p, s) -> Call.openURI(p.player.con, Config.RULE_URL));
        row();

        option(Iconc.link + "[green]" + translated.get(1),
                (p, s) -> Call.openURI(p.player.con, Config.MINDUSTRY_TOOL_URL));
        row();

        option(Iconc.discord + "[blue]" + translated.get(2),
                (p, s) -> Call.openURI(p.player.con, Config.DISCORD_INVITE_URL));
        row();

        text(Iconc.cancel + "[red]" + translated.get(3));
    }
}
