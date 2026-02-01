package plugin.menus;

import mindustry.gen.Call;
import plugin.Config;
import plugin.type.Session;

public class HubMenu extends PluginMenu<String> {
    @Override
    public void build(Session session, String loginLink) {
        this.title = "Servers";
        this.description = Config.HUB_MESSAGE;

        if (loginLink != null && !loginLink.isEmpty()) {
            option("[green]Login via MindustryTool", (p, s) -> Call.openURI(p.player.con, s));
            row();
        }

        option("[green]Rules", (p, s) -> Call.openURI(p.player.con, Config.RULE_URL));
        row();

        option("[green]Website", (p, s) -> Call.openURI(p.player.con, Config.MINDUSTRY_TOOL_URL));
        row();

        option("[blue]Discord", (p, s) -> Call.openURI(p.player.con, Config.DISCORD_INVITE_URL));
        row();

        option("[red]Close", (p, s) -> new ServerListMenu().send(session, 0));
    }
}
