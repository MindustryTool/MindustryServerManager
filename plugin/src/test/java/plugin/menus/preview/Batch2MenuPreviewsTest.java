package plugin.menus.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import common.util.Pair;
import mindustry.game.Team;
import org.junit.jupiter.api.Test;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.menu.AIPickMenu;
import plugin.gamemode.catali.menu.AbandonMenu;
import plugin.gamemode.catali.menu.CommonUpgradeMenu;
import plugin.gamemode.catali.menu.CommonUpgradeQuantityMenu;
import plugin.gamemode.catali.menu.JoinTeamMenu;
import plugin.gamemode.catali.menu.RareUpgradeBuffSelectBuffMenu;
import plugin.gamemode.catali.menu.RareUpgradeBuffSelectUnitMenu;
import plugin.gamemode.catali.menu.RareUpgradeMenu;
import plugin.gamemode.catali.menu.RareUpgradeTierSelectUnitMenu;
import plugin.gamemode.sandbox.SandboxTeamMenu;
import plugin.session.Session;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Batch2MenuPreviewsTest {

    private static final Path OUTPUT_DIR = Path.of("build", "menu-previews", "batch2");

    @Test
    void renderBatch2MenusInAllViewports() {
        CataliTeamData team = new CataliTeamData(Team.sharded, "uuid_dev_test");
        team.level.commonUpgradePoints = 5;
        team.level.rareUpgradePoints = 3;
        team.useCommandAi = false;

        AbandonMenu abandonMenu = new AbandonMenu(null);
        abandonMenu.setState(team);

        AIPickMenu aiPickMenu = new AIPickMenu();
        aiPickMenu.setState(team);

        CommonUpgradeQuantityMenu quantityMenu = new CommonUpgradeQuantityMenu();
        quantityMenu.setState(Pair.of(team, CataliCommonUpgrade.DAMAGE));

        CommonUpgradeMenu commonUpgradeMenu = new CommonUpgradeMenu(quantityMenu);
        commonUpgradeMenu.setState(team);

        RareUpgradeMenu rareUpgradeMenu = new RareUpgradeMenu(
                new RareUpgradeTierSelectUnitMenu(null, null),
                new RareUpgradeBuffSelectUnitMenu((RareUpgradeBuffSelectBuffMenu) null));
        rareUpgradeMenu.setState(team);

        JoinTeamMenu joinTeamMenu = new JoinTeamMenu();
        SandboxTeamMenu sandboxTeamMenu = new SandboxTeamMenu();

        MenuViewport[] viewports = {
                MenuViewport.DESKTOP,
                MenuViewport.MOBILE_LANDSCAPE,
                MenuViewport.MOBILE_PORTRAIT
        };

        for (MenuViewport vp : viewports) {
            String suffix = "_" + vp.name().toLowerCase() + ".png";
            Session session = MockSession.builder().locale("en").mobile(vp.mobile).build();

            MenuSnapshot.builder().menu(abandonMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("abandon" + suffix));
            MenuSnapshot.builder().menu(aiPickMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("aipick" + suffix));
            MenuSnapshot.builder().menu(commonUpgradeMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("common_upgrade" + suffix));
            MenuSnapshot.builder().menu(quantityMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("upgrade_quantity" + suffix));
            MenuSnapshot.builder().menu(rareUpgradeMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("rare_upgrade" + suffix));
            MenuSnapshot.builder().menu(joinTeamMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("jointeam" + suffix));
            MenuSnapshot.builder().menu(sandboxTeamMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("sandbox_team" + suffix));
        }

        assertTrue(Files.exists(OUTPUT_DIR.resolve("aipick_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("common_upgrade_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("upgrade_quantity_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("rare_upgrade_mobile_landscape.png")));
    }
}
