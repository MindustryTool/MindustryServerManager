package plugin.menus;

import mindustry.gen.Groups;
import mindustry.gen.Player;

public class GriefMenu extends PluginMenu<Player> {
    @Override
    public void build(Player player, Player target) {
        this.title = "Grief Report";

        if (target == null) {
            Groups.player.each(p -> {
                option(p.name, (_p, s) -> {

                });
                row();
            });

            text("[red]Close");
        } else {
            option("Report", (p, s) -> {
            });
            row();
            text("Cancel");
        }
    }
}
