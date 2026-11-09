package plugin.gamemode.catali.menu;

import java.util.Objects;
import common.util.Pair;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Unit;
import mindustry.ui.builder.UiBuilder;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.core.Registry;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliBuffRareUpgrade;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class RareUpgradeBuffSelectBuffMenu extends ServerMenu<Pair<CataliTeamData, Unit>> {

    @Override
    public void build(MenuContext<Pair<CataliTeamData, Unit>> ctx) {
        Session session = ctx.getSession();
        Pair<CataliTeamData, Unit> pair = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(pair);

        CataliTeamData team = pair.first;
        Unit unit = pair.second;

        this.title = Tr.t(session, "catali.select_buff");

        ctx.label("[lightgray]" + Tr.t(session, "catali.choose_buff") + " " + Tr.t(session, "catali.for_unit") + " "
                + unit.type.emoji() + " [accent]" + unit.type.name + "[]").width(MenuContext.SAFE_WIDTH);
        ctx.row();

        var availableEffects = CataliConfig.selectBuffsCanBeApplied(unit);

        ctx.pane(MenuContext.SAFE_WIDTH, 220f, paneTable -> {
            if (availableEffects.isEmpty()) {
                paneTable.add(UiBuilder.label(Tr.t(session, "catali.no_applicable_buffs")).wrap().align("left").growX()).row();
                return;
            }

            for (var effect : availableEffects) {
                var card = UiBuilder.table().align("left");
                card.width(MenuContext.SAFE_CARD_WIDTH);

                card.add(UiBuilder.label(effect.emoji() + " [white]" + effect.name + "[]").wrap().align("left").growX().padLeft(4f));

                String actId = ctx.registerAction((sess, p, res) -> {
                    PluginEvents.fire(new CataliBuffRareUpgrade(team, unit, effect));
                });

                card.add(UiBuilder.button(Tr.t(session, "catali.apply"))
                        .clicked(actId)
                        .width(95f)
                        .height(34f)
                        .align("right"));

                paneTable.add(card.growX().pad(2f)).row();
            }
        });

        ctx.row();

        ctx.cancelButton(Tr.t(session, "catali.back"), (sess, p, res) -> {
            Registry.get(RareUpgradeBuffSelectUnitMenu.class).send(sess, team);
        });
    }
}
