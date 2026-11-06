package plugin.grief;

import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.session.SessionService;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class GriefMenu extends ServerMenu<Session> {

    private final SessionService sessionService;
    private final AdminService adminService;

    @Override
    public void build(MenuContext<Session> ctx) {
        Session target = ctx.getState();
        this.title = Tr.t(ctx.getSession().locale, "grief.report_title");

        if (target == null) {
            ctx.label(Tr.t(ctx.getSession().locale, "grief.report_title")).width(MenuContext.SAFE_WIDTH);
            ctx.row();

            ctx.pane(MenuContext.SAFE_WIDTH, 280f, paneTable -> {
                sessionService.each(t -> t != ctx.getSession(), t -> {
                    TableBuilder card = UiBuilder.table().pad(4f);
                    card.width(MenuContext.SAFE_CARD_WIDTH);
                    card.add(UiBuilder.label("[scarlet]" + t.player.name).wrap().align("left").growX());

                    String reportAct = ctx.registerAction((sess, st, res) -> Registry.get(GriefMenu.class).send(sess, t));
                    card.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "grief.report")).clicked(reportAct).width(90f).height(34f).align("right").padLeft(8f));

                    paneTable.add(card.growX().pad(2f)).row();
                });
            });

            ctx.row();
            ctx.closeButton(Tr.t(ctx.getSession().locale, "grief.close"));
        } else {
            ctx.label("[yellow]Report player: [scarlet]" + target.player.name + "[yellow]?").width(MenuContext.SAFE_WIDTH);
            ctx.row();

            ctx.table(actions -> {
                String confirmAct = ctx.registerAction((sess, st, res) -> adminService.reportGrief(sess, target));
                String cancelAct = ctx.registerAction((sess, st, res) -> Registry.get(GriefMenu.class).send(sess, null));

                actions.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "grief.report")).clicked(confirmAct).width(120f).height(38f).pad(4f));
                actions.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "grief.cancel")).clicked(cancelAct).width(120f).height(38f).pad(4f));
            });
        }
    }
}
