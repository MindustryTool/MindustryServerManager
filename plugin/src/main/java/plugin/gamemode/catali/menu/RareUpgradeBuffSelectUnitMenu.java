package plugin.gamemode.catali.menu;

import java.util.Objects;
import common.util.Pair;
import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
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

        ctx.label("[lightgray]" + Tr.t(session, "catali.choose_unit_for_buff"));
        ctx.row();

        var units = team.units();

        ctx.pane(220f, paneTable -> {
            if (units.isEmpty()) {
                paneTable.add(UiBuilder.label(Tr.t(session, "catali.no_units_available")).wrap().align("left").growX()).row();
                return;
            }

            for (var unit : units) {
                var card = UiBuilder.table().align("left");
                card.width(ctx.safeCardWidth());

                String label = unit.type.emoji() + " " + Tr.t(session, "catali.unit_hp", "name", unit.type.name, "hp", Math.round(unit.health()), "maxhp", Math.round(unit.maxHealth()));
                card.add(UiBuilder.label(label).wrap().align("left").growX().padLeft(4f));

                String actId = ctx.registerAction((sess, t, res) -> {
                    buffSelectMenu.send(sess, Pair.of(team, unit));
                });

                card.add(UiBuilder.button(Tr.t(session, "catali.select"))
                        .clicked(actId)
                        .width(95f)
                        .height(48f)
                        .align("right"));

                paneTable.add(card.growX().pad(2f)).row();
            }
        });
    }
}
