package plugin.menus;

import mindustry.gen.Call;
import plugin.Config;
import plugin.type.Session;
import plugin.handler.ApiGateway;

public class HubMenu extends PluginMenu<String> {
    @Override
    public void build(Session session, String loginLink) {
        this.title = ApiGateway.translate(session.locale, "@Servers");
        this.description = ApiGateway.translate(session.locale, "@" + Config.HUB_MESSAGE);

        if (loginLink != null && !loginLink.isEmpty()) {
            option(ApiGateway.translate(session.locale, "[green]", "@Login via MindustryTool"),
                    (p, s) -> Call.openURI(p.player.con, s));
            row();
        }

        option(ApiGateway.translate(session.locale, "[green]", "@Rules"),
                (p, s) -> Call.openURI(p.player.con, Config.RULE_URL));
        row();

        option(ApiGateway.translate(session.locale, "[green]", "@Website"),
                (p, s) -> Call.openURI(p.player.con, Config.MINDUSTRY_TOOL_URL));
        row();

        option(ApiGateway.translate(session.locale, "[blue]", "@Discord"),
                (p, s) -> Call.openURI(p.player.con, Config.DISCORD_INVITE_URL));
        row();

        option(ApiGateway.translate(session.locale, "[red]", "@Close"),
                (p, s) -> new ServerListMenu().send(session, 0));
    }
}
