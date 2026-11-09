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
            ctx.label(Tr.t(ctx.getSession().locale, "grief.report_title"));
            ctx.row();

            ctx.pane(280f, paneTable -> {
                sessionService.each(t -> t != ctx.getSession(), t -> {
                    TableBuilder card = UiBuilder.table().align("left");
                    card.width(ctx.safeCardWidth());
                    card.add(UiBuilder.label("[white]" + t.player.name).wrap().align("left").growX().padLeft(4f));

                    String reportAct = ctx.registerAction((sess, st, res) -> Registry.get(GriefMenu.class).send(sess, t));
                    card.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "grief.report")).clicked(reportAct).width(90f).height(48f).align("right").padLeft(8f));

                    paneTable.add(card.growX().pad(2f)).row();
                });
            });
        } else {
            ctx.label(Tr.t(ctx.getSession(), "grief.report_player", "player", target.player.name));
            ctx.row();

            ctx.table(actions -> {
                String confirmAct = ctx.registerAction((sess, st, res) -> adminService.reportGrief(sess, target));

                actions.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "grief.report")).clicked(confirmAct).width(120f).height(48f).pad(4f));
            });
        }
    }
}
