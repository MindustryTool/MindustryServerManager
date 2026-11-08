package plugin.session;

import arc.struct.Seq;
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

        Seq<Session> targets = new Seq<>();
        sessionService.each(targets::add);

        ctx.pane(MenuContext.SAFE_WIDTH, 260f, paneTable -> {
            if (targets.isEmpty()) {
                paneTable.add(UiBuilder.label("[lightgray]No players online").wrap().align("left").growX().pad(8f)).row();
                return;
            }

            for (Session target : targets) {
                TableBuilder card = UiBuilder.table().pad(4f);
                card.width(MenuContext.SAFE_CARD_WIDTH);
                String nameText = target.player.name + " [gray](#" + target.player.id() + ")";
                card.add(UiBuilder.label("[yellow]" + nameText).wrap().align("left").growX());

                String inspectAct = ctx.registerAction((sess, st, res) -> {
                    caller.player.sendMessage(SessionUtils.getInfoString(target, target.getData()));
                });

                card.add(UiBuilder.button("View Info").clicked(inspectAct).width(100f).height(36f).align("right").padLeft(6f));
                paneTable.add(card.growX().pad(2f)).row();
            }
        });
    }
}
