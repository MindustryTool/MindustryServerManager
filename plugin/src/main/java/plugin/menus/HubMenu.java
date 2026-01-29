package plugin.menus;

import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.Config;
import plugin.handler.EventHandler;

public class HubMenu extends PluginMenu<String> {
    @Override
    public void build(Player player, String loginLink) {
        this.title = "Servers";
        this.description = Config.HUB_MESSAGE;

        if (loginLink != null && !loginLink.isEmpty()) {
            row();
            option("[green]Login via MindustryTool", (p, s) -> Call.openURI(p.con, s));
        }

        row();
        option("[green]Rules", (p, s) -> Call.openURI(p.con, Config.RULE_URL));
        
        row();
        option("[green]Website", (p, s) -> Call.openURI(p.con, Config.MINDUSTRY_TOOL_URL));
        
        row();
        option("[blue]Discord", (p, s) -> Call.openURI(p.con, Config.DISCORD_INVITE_URL));
        
        row();
        option("[red]Close", (p, s) -> EventHandler.sendServerList(p, 0));
    }
}
