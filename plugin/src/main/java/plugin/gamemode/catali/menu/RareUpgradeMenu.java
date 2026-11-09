package plugin.gamemode.catali.menu;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliSpawnRareUpgrade;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class RareUpgradeMenu extends ServerMenu<CataliTeamData> {

    private final RareUpgradeTierSelectUnitMenu tierUnitMenu;
    private final RareUpgradeBuffSelectUnitMenu buffUnitMenu;

    @Override
    public boolean valid() {
        return super.valid() && state != null && state.level != null && state.level.rareUpgradePoints > 0;
    }

    @Override
    public void build(MenuContext<CataliTeamData> ctx) {
        Session session = ctx.getSession();
        CataliTeamData team = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(team);

        this.title = Tr.t(session, "catali.rare_upgrades");

        int points = team.level.rareUpgradePoints;
        ctx.label(Tr.t(session, "catali.available_rare_points", "points", points) + "[] - "
                + Tr.t(session, "catali.select_rare_upgrade_type"));
        ctx.row();

        var table = UiBuilder.table().pad(6f);

        // Option 1: Spawn Unit
        if (team.canHaveMoreUnit()) {
            var spawnCard = UiBuilder.table().align("left");
            spawnCard.add(UiBuilder.label("[accent]" + Tr.t(session, "catali.spawn_unit") + "[]").wrap().align("left").growX().padLeft(4f));
            String spawnAct = ctx.registerAction((sess, t, res) -> {
                PluginEvents.fire(new CataliSpawnRareUpgrade(team));
            });
            spawnCard.add(UiBuilder.button(Tr.t(session, "catali.spawn")).clicked(spawnAct).width(95f).height(32f).align("right").pad(4f));
            table.add(spawnCard.growX().pad(2f)).row();
        }

        // Option 2: Evolve Unit
        var evolveCard = UiBuilder.table().align("left");
        evolveCard.add(UiBuilder.label("[accent]" + Tr.t(session, "catali.evolve_unit") + "[]").wrap().align("left").growX().padLeft(4f));
        String evolveAct = ctx.registerAction((sess, t, res) -> {
            tierUnitMenu.send(sess, team);
        });
        evolveCard.add(UiBuilder.button("[accent]" + Tr.t(session, "catali.evolve") + "[]").clicked(evolveAct).width(95f).height(32f).align("right").pad(4f));
        table.add(evolveCard.growX().pad(2f)).row();

        // Option 3: Apply Buff
        var buffCard = UiBuilder.table().align("left");
        buffCard.add(UiBuilder.label("[accent]" + Tr.t(session, "catali.apply_buff") + "[]").wrap().align("left").growX().padLeft(4f));
        String buffAct = ctx.registerAction((sess, t, res) -> {
            buffUnitMenu.send(sess, team);
        });
        buffCard.add(UiBuilder.button(Tr.t(session, "catali.buff")).clicked(buffAct).width(95f).height(32f).align("right").pad(4f));
        table.add(buffCard.growX().pad(2f)).row();

        ctx.add(table.growX());
    }
}
