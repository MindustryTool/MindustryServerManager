package plugin.gamemode.catali.menu;

import java.util.Objects;
import common.util.Pair;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.core.Registry;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class CommonUpgradeQuantityMenu extends ServerMenu<Pair<CataliTeamData, CataliCommonUpgrade>> {

    @Override
    public void build(MenuContext<Pair<CataliTeamData, CataliCommonUpgrade>> ctx) {
        Session session = ctx.getSession();
        Pair<CataliTeamData, CataliCommonUpgrade> pair = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(pair);

        CataliTeamData team = pair.first;
        CataliCommonUpgrade upgrade = pair.second;
        var level = team.level;

        this.title = Tr.t(session, "catali.upgrade_quantity");

        String upgradeName = Tr.t(session, "catali." + upgrade.name().toLowerCase());
        ctx.label("[accent]" + upgradeName + "[] [lightgray]- " + Tr.t(session, "catali.select_points_to_spend")).width(MenuContext.SAFE_WIDTH);
        ctx.row();

        var container = UiBuilder.table().pad(8f);

        // Quick amount buttons
        var btnTable = UiBuilder.table().pad(4f);

        // 1 point
        String act1 = ctx.registerAction((sess, p, res) -> applyPoints(sess, team, upgrade, 1));
        btnTable.add(UiBuilder.button("+1 Point").clicked(act1).width(90f).height(40f).pad(3f));

        // 2 points
        if (level.commonUpgradePoints >= 2) {
            String act2 = ctx.registerAction((sess, p, res) -> applyPoints(sess, team, upgrade, 2));
            btnTable.add(UiBuilder.button("+2 Points").clicked(act2).width(90f).height(40f).pad(3f));
        }

        // 5 points
        if (level.commonUpgradePoints >= 5) {
            String act5 = ctx.registerAction((sess, p, res) -> applyPoints(sess, team, upgrade, 5));
            btnTable.add(UiBuilder.button("+5 Points").clicked(act5).width(90f).height(40f).pad(3f));
        }

        btnTable.row();

        // All points
        String actAll = ctx.registerAction((sess, p, res) -> applyPoints(sess, team, upgrade, level.commonUpgradePoints));
        btnTable.add(UiBuilder.button(Tr.t(session, "catali.all_points") + " (" + level.commonUpgradePoints + ")")
                .clicked(actAll)
                .growX()
                .height(40f)
                .colspan(3)
                .pad(4f));

        container.add(btnTable).row();
        ctx.add(container);
        ctx.row();

        // Navigation bar
        var navBar = UiBuilder.table().pad(4f);
        String backAct = ctx.registerAction((sess, p, res) -> {
            Registry.get(CommonUpgradeMenu.class).send(sess, team);
        });
        navBar.add(UiBuilder.button(Tr.t(session, "catali.back")).clicked(backAct).width(120f).height(38f).pad(3f));

        ctx.add(navBar);
    }

    private void applyPoints(Session session, CataliTeamData team, CataliCommonUpgrade upgrade, int amount) {
        team.consumeUpgrade(upgrade, amount);
        if (team.level.commonUpgradePoints > 0) {
            Registry.get(CommonUpgradeMenu.class).send(session, team);
        }
    }
}
