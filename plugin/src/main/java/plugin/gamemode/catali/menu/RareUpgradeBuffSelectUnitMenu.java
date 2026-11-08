package plugin.gamemode.catali.menu;

import java.util.Objects;
import common.util.Pair;
import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.core.Registry;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class RareUpgradeBuffSelectUnitMenu extends ServerMenu<CataliTeamData> {

    private final RareUpgradeBuffSelectBuffMenu buffSelectMenu;

    @Override
    public void build(MenuContext<CataliTeamData> ctx) {
        Session session = ctx.getSession();
        CataliTeamData team = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(team);

        this.title = Tr.t(session, "catali.select_unit_for_buff");

        ctx.label("[lightgray]" + Tr.t(session, "catali.choose_unit_for_buff")).width(MenuContext.SAFE_WIDTH);
        ctx.row();

        var units = team.units();

        ctx.pane(MenuContext.SAFE_WIDTH, 220f, paneTable -> {
            if (units.isEmpty()) {
                paneTable.add(UiBuilder.label("[lightgray]No units available.").wrap().align("left").growX()).row();
                return;
            }

            for (var unit : units) {
                var card = UiBuilder.table().align("left");
                card.width(MenuContext.SAFE_CARD_WIDTH);

                String label = unit.type.emoji() + " [white]" + unit.type.name + "[] [gray]("
                        + Math.round(unit.health()) + "/" + Math.round(unit.maxHealth()) + " HP)[]";
                card.add(UiBuilder.label(label).wrap().align("left").growX().padLeft(4f));

                String actId = ctx.registerAction((sess, t, res) -> {
                    buffSelectMenu.send(sess, Pair.of(team, unit));
                });

                card.add(UiBuilder.button("[accent]Select[]")
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
