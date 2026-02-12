package plugin.gamemode.catali.command.client;

import plugin.annotations.Gamemode;
import plugin.commands.PluginClientCommand;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.menu.CommonUpgradeMenu;
import plugin.gamemode.catali.menu.RareUpgradeMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
public class UpgradeCommand extends PluginClientCommand {

    public UpgradeCommand() {
        setName("u");
        setDescription("Show upgrade menu");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        var player = session.player;
        var team = Registry.get(CataliGamemode.class).findTeam(player);

        if (team == null) {
            session.player.sendMessage(I18n.t(player, "@Use", "[accent]/p[white]", "@to start a new team"));
        } else {
            var showed = false;
            if (team.level.commonUpgradePoints > 0) {
                new CommonUpgradeMenu().send(session, team);
                showed = true;
            }

            if (team.level.rareUpgradePoints > 0) {
                new RareUpgradeMenu().send(session, team);
                showed = true;
            }

            if (!showed) {
                session.player.sendMessage(I18n.t(player, "@You have no upgrade points"));
            }
        }
    }

}
