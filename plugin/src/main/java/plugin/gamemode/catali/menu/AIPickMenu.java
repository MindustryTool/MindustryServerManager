package plugin.gamemode.catali.menu;

import java.util.Objects;

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
public class AIPickMenu extends ServerMenu<CataliTeamData> {

    @Override
    public void build(MenuContext<CataliTeamData> ctx) {
        Session session = ctx.getSession();
        CataliTeamData team = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(team);

        this.title = Tr.t(session, "catali.choose_controller");

        ctx.label("[lightgray]" + Tr.t(session, "catali.controller_description"));
        ctx.row();

        var container = UiBuilder.table().pad(8f);

        // Classic Controller Card
        var classicCard = UiBuilder.table().align("left");
        classicCard.add(UiBuilder.label("[accent]" + Tr.t(session, "catali.classic_controller") + "[]").align("left").growX().padLeft(4f));
        if (!team.useCommandAi) {
            classicCard.add(UiBuilder.label("[stat](Active Controller)[]").align("right").padLeft(6f));
        } else {
            String actId = ctx.registerAction((sess, t, res) -> {
                team.useCommandAi = false;
                sess.player.sendMessage("[green]Switched to Classic Controller.");
            });
            classicCard.add(UiBuilder.button("[accent]Select[]").clicked(actId).width(100f).height(32f).padLeft(6f));
        }
        container.add(classicCard.growX().pad(4f)).row();

        // Command Controller Card
        var cmdCard = UiBuilder.table().align("left");
        cmdCard.add(UiBuilder.label("[accent]" + Tr.t(session, "catali.command_controller") + "[]").align("left").growX().padLeft(4f));
        if (team.useCommandAi) {
            cmdCard.add(UiBuilder.label("[stat](Active Controller)[]").align("right").padLeft(6f));
        } else {
            String actId = ctx.registerAction((sess, t, res) -> {
                team.useCommandAi = true;
                sess.player.sendMessage("[green]Switched to Command Controller.");
            });
            cmdCard.add(UiBuilder.button("[accent]Select[]").clicked(actId).width(100f).height(32f).padLeft(6f));
        }
        container.add(cmdCard.growX().pad(4f)).row();

        ctx.add(container.growX());
    }
}
