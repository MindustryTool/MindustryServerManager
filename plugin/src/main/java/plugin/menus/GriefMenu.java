package plugin.menus;

import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.utils.AdminUtils;

public class GriefMenu extends PluginMenu<Player> {
    @Override
    public void build(Player player, Player target) {
        this.title = "Grief Report";

        if (target == null) {
            Groups.player.each(p -> {
                option(p.name, (_p, s) -> new GriefMenu().send(player, s));
                row();
            });

            text("[red]Close");
        } else {
            option("Report", (p, s) -> AdminUtils.reportGrief(player, target));
            row();
            text("Cancel");
        }
    }
}
