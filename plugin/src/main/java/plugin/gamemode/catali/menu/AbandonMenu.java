package plugin.gamemode.catali.menu;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class AbandonMenu extends ServerMenu<CataliTeamData> {

    private final CataliGamemode gamemode;

    @Override
    public void build(MenuContext<CataliTeamData> ctx) {
        Session session = ctx.getSession();
        CataliTeamData team = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(team);

        this.title = Tr.t(session, "catali.abandon_menu_title");

        ctx.label("[lightgray]" + Tr.t(session, "catali.abandon_menu_desc"));
        ctx.row();

        var units = team.units();
        ctx.pane(MenuContext.SAFE_WIDTH, 220f, paneTable -> {
            if (units.isEmpty()) {
                paneTable.add(UiBuilder.label("[lightgray]" + Tr.t(session, "catali.no_units"))).row();
                return;
            }

            for (var unit : units) {
                var card = UiBuilder.table().pad(4f);
                card.width(MenuContext.SAFE_CARD_WIDTH);

                String unitInfo = unit.type.emoji() + " [accent]" + unit.type.name + "[] [gray]("
                        + Math.round(unit.health()) + "/" + Math.round(unit.maxHealth()) + " HP)[]";
                card.add(UiBuilder.label(unitInfo).wrap().align("left").growX().pad(4f));

                String actId = ctx.registerAction((sess, t, res) -> gamemode.abandonUnit(unit));
                card.add(UiBuilder.button("[scarlet]" + Tr.t(session, "catali.abandon_unit") + "[]")
                        .clicked(actId)
                        .width(95f)
                        .height(32f)
                        .align("right"));

                paneTable.add(card.growX().pad(2f)).row();
            }
        });

        ctx.row();
        String abandonTeamAct = ctx.registerAction((sess, t, res) -> gamemode.abandonTeam(team));
        ctx.add(UiBuilder.button("[scarlet]" + Tr.t(session, "catali.abandon_team") + "[]")
                .clicked(abandonTeamAct)
                .width(240f)
                .height(40f));
        ctx.row();
        ctx.closeButton(Tr.t(session, "catali.close"));
    }
}
