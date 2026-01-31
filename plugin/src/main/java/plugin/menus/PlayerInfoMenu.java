package plugin.menus;

import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.handler.SessionHandler;

public class PlayerInfoMenu extends PluginMenu<Player> {
    @Override
    public void build(Player player, Player caller) {
        this.title = "Servers";

        Groups.player.each(p -> {
            option(p.name, (_p, s) -> {
                SessionHandler.get(p).ifPresent(session -> caller.sendMessage(session.info()));
            });
            row();
        });

        option("[red]Close", (p, s) -> new ServerListMenu().send(player, 0));
    }
}
