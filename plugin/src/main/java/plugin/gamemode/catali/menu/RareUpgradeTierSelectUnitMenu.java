package plugin.gamemode.catali.menu;

import java.util.Objects;
import common.util.Pair;
import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.core.Registry;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class RareUpgradeTierSelectUnitMenu extends ServerMenu<CataliTeamData> {

    private final CataliConfig config;
    private final RareUpgradeTierSelectUpgradeMenu upgradeMenu;

    @Override
    public void build(MenuContext<CataliTeamData> ctx) {
        Session session = ctx.getSession();
        CataliTeamData team = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(team);

        this.title = Tr.t(session, "catali.select_unit_to_evolve");

        ctx.label("[lightgray]" + Tr.t(session, "catali.choose_unit_to_evolve")).width(MenuContext.SAFE_WIDTH);
        ctx.row();

        var units = team.getUpgradeableUnits();

        ctx.pane(MenuContext.SAFE_WIDTH, 220f, paneTable -> {
            if (units.isEmpty()) {
                paneTable.add(UiBuilder.label(Tr.t(session, "catali.no_upgradeable_units")).wrap().align("left").growX()).row();
                return;
            }

            for (var unit : units) {
                var evolutions = config.getUnitEvolutions(unit.type);
                if (evolutions.isEmpty()) {
                    continue;
                }

                var card = UiBuilder.table().align("left");
                card.width(MenuContext.SAFE_CARD_WIDTH);

                String label = unit.type.emoji() + " " + Tr.t(session, "catali.unit_branches", "name", unit.type.name, "branches", evolutions.size());
                card.add(UiBuilder.label(label).wrap().align("left").growX().padLeft(4f));

                String actId = ctx.registerAction((sess, t, res) -> {
                    upgradeMenu.send(sess, Pair.of(team, unit));
                });

                card.add(UiBuilder.button("[accent]" + Tr.t(session, "catali.evolve") + "[]")
                        .clicked(actId)
                        .width(95f)
                        .height(34f)
                        .align("right"));

                paneTable.add(card.growX().pad(2f)).row();
            }
        });

        ctx.row();

        var navBar = UiBuilder.table().pad(4f);
        String backAct = ctx.registerAction((sess, t, res) -> {
            Registry.get(RareUpgradeMenu.class).send(sess, team);
        });
        navBar.add(UiBuilder.button(Tr.t(session, "catali.back")).clicked(backAct).width(120f).height(38f).pad(3f));

        ctx.add(navBar);
    }
}
