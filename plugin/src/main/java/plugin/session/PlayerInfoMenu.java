package plugin.session;

import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class PlayerInfoMenu extends ServerMenu<Session> {

    private final SessionService sessionService;

    @Override
    public void build(MenuContext<Session> ctx) {
        Session caller = ctx.getState() != null ? ctx.getState() : ctx.getSession();
        this.title = Tr.t(ctx.getSession().locale, "session.players_title");

        ctx.label("[accent]Select an online player to view stats:");
        ctx.row();

        ctx.pane(MenuContext.SAFE_WIDTH, 300f, paneTable -> {
            sessionService.each(target -> {
                TableBuilder card = UiBuilder.table().pad(4f);
                String nameText = target.player.name + " [gray](#" + target.player.id() + ")";
                card.add(UiBuilder.label("[yellow]" + nameText).align("left"));

                String inspectAct = ctx.registerAction((sess, st, res) -> {
                    caller.player.sendMessage(SessionUtils.getInfoString(target, target.getData()));
                });

                card.add(UiBuilder.button("View Info").clicked(inspectAct).width(110f).height(38f).align("right").padLeft(8f));
                paneTable.add(card.growX().pad(2f)).row();
            });
        });

        ctx.row();
        ctx.closeButton(Tr.t(ctx.getSession().locale, "session.close"));
    }
}
