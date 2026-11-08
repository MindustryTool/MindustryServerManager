package plugin.gamemode.catali.menu;

import java.util.Objects;

import common.util.Pair;
import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class CommonUpgradeMenu extends ServerMenu<CataliTeamData> {

    private final CommonUpgradeQuantityMenu quantityMenu;

    @Override
    public boolean valid() {
        return super.valid() && state != null && state.level != null && state.level.commonUpgradePoints > 0;
    }

    @Override
    public void build(MenuContext<CataliTeamData> ctx) {
        Session session = ctx.getSession();
        CataliTeamData team = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(team);

        this.title = Tr.t(session, "catali.common_upgrades");

        int points = team.level.commonUpgradePoints;
        ctx.label("[gold]Points: " + points + "[] - "
                + Tr.t(session, "catali.upgrade_points_description", "points", points));
        ctx.row();

        var upgrades = team.upgrades;

        ctx.pane(MenuContext.SAFE_WIDTH, 260f, paneTable -> {
            // Damage Card
            addUpgradeCard(ctx, paneTable, session, team, CataliCommonUpgrade.DAMAGE,
                    Tr.t(session, "catali.damage"),
                    "Lv " + upgrades.getDamageLevel() + " (+" + Math.round((upgrades.getDamageMultiplier() - 1f) * 100f) + "%)");

            // Health Card
            addUpgradeCard(ctx, paneTable, session, team, CataliCommonUpgrade.HEALTH,
                    Tr.t(session, "catali.health"),
                    "Lv " + upgrades.getHealthLevel() + " (+" + Math.round((upgrades.getHealthMultiplier() - 1f) * 100f) + "%)");

            // Regen Card
            addUpgradeCard(ctx, paneTable, session, team, CataliCommonUpgrade.REGEN,
                    Tr.t(session, "catali.regeneration"),
                    "Lv " + upgrades.getRegenLevel() + " (+" + Math.round((upgrades.getRegenMultiplier() - 1f) * 100f) + "%)");

            // Exp Card
            addUpgradeCard(ctx, paneTable, session, team, CataliCommonUpgrade.EXP,
                    Tr.t(session, "catali.experience"),
                    "Lv " + upgrades.getExpLevel() + " (+" + Math.round((upgrades.getExpMultiplier() - 1f) * 100f) + "%)");
        });
    }

    private void addUpgradeCard(MenuContext<CataliTeamData> ctx, TableBuilder table,
                               Session session, CataliTeamData team, CataliCommonUpgrade upgrade,
                               String title, String stats) {
        var card = UiBuilder.table().align("left");
        card.width(MenuContext.SAFE_CARD_WIDTH);

        var info = UiBuilder.table().align("left");
        info.add(UiBuilder.label("[accent]" + title + "[]").wrap().align("left").growX()).row();
        info.add(UiBuilder.label("[lightgray]" + stats + "[]").wrap().align("left").growX());
        card.add(info.growX().padLeft(4f));

        String actId = ctx.registerAction((sess, t, res) -> {
            if (team.level.commonUpgradePoints == 1) {
                team.consumeUpgrade(upgrade, 1);
            } else if (team.level.commonUpgradePoints > 1) {
                quantityMenu.send(sess, Pair.of(team, upgrade));
            }
        });

        card.add(UiBuilder.button("[green]Upgrade[]").clicked(actId).width(105f).height(32f).align("right").pad(4f));
        table.add(card.growX().pad(2f)).row();
    }
}
