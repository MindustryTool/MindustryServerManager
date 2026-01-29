package plugin.menus;

import mindustry.gen.Player;
import plugin.handler.EventHandler;
import dto.ServerDto;

public class ServerRedirectMenu extends PluginMenu<ServerDto> {
    @Override
    public void build(Player player, ServerDto serverData) {
        this.title = "Redirect";
        this.description = "Do you want to go to server: " + serverData.getName();

        text("[red]No");
        option("[green]Yes", (p, s) -> EventHandler.onServerChoose(p, s.getId().toString(), s.getName()));
    }
}
