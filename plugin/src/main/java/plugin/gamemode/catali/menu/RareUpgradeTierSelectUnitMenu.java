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

        ctx.label("[lightgray]" + Tr.t(session, "catali.choose_unit_to_evolve"));
        ctx.row();

        var units = team.getUpgradeableUnits();

        ctx.pane(MenuContext.SAFE_WIDTH, 220f, paneTable -> {
            if (units.isEmpty()) {
                paneTable.add(UiBuilder.label("[lightgray]No upgradeable units available.")).row();
                return;
            }

            for (var unit : units) {
                var evolutions = config.getUnitEvolutions(unit.type);
                if (evolutions.isEmpty()) {
                    continue;
                }

                var card = UiBuilder.table().pad(4f);
                card.width(MenuContext.SAFE_CARD_WIDTH);

                String label = unit.type.emoji() + " [white]" + unit.type.name + "[] [gray]("
                        + evolutions.size() + " branches)[]";
                card.add(UiBuilder.label(label).align("left")).pad(4f);

                String actId = ctx.registerAction((sess, t, res) -> {
                    upgradeMenu.send(sess, Pair.of(team, unit));
                });

                card.add(UiBuilder.button("[accent]Evolve[]")
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
        navBar.add(UiBuilder.button(Tr.t(session, "catali.back")).clicked(backAct).width(105f).height(38f).pad(3f));

        String closeAct = ctx.registerAction((sess, t, res) -> {});
        navBar.add(UiBuilder.button(Tr.t(session, "catali.close")).clicked(closeAct).width(105f).height(38f).pad(3f));

        ctx.add(navBar);
    }
}
