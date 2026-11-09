package plugin.trail;

import arc.struct.Seq;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.SessionRepository;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class TrailMenu extends ServerMenu<Integer> {

    private final TrailService trailService;
    private final SessionRepository sessionRepository;

    @Override
    public void build(MenuContext<Integer> ctx) {
        var trails = Seq.with(trailService.trails.values());
        this.title = Tr.t(ctx.getSession(), "trail.menu_title");

        boolean hasTrail = ctx.getSession().getData().trail != null && !ctx.getSession().getData().trail.isEmpty();
        ctx.label(Tr.t(ctx.getSession(), "trail.choose_trail")).width(MenuContext.SAFE_WIDTH);
        ctx.row();

        if (hasTrail) {
            ctx.button(Tr.t(ctx.getSession(), "trail.clear_active"), (sess, st, res) -> {
                sess.getData().trail = "";
                if (sessionRepository != null) {
                    sessionRepository.markDirty(sess);
                }
                sess.player.sendMessage(Tr.t(sess, "trail.removed"));
                Registry.get(TrailMenu.class).send(sess, 0);
            }).growX().height(48f).pad(4f);
            ctx.row();
        }

        ctx.pane(MenuContext.SAFE_WIDTH, 320f, paneTable -> {
            for (var trail : trails) {
                boolean owns = trail.owns(ctx.getSession());
                boolean isCurrent = trail.getName().equals(ctx.getSession().getData().trail);

                TableBuilder card = UiBuilder.table().align("left");
                card.width(MenuContext.SAFE_CARD_WIDTH);
                String badge = isCurrent ? "[green]" + Iconc.ok + " " + Tr.t(ctx.getSession(), "trail.equipped_text")
                    : owns ? "[accent]" + Tr.t(ctx.getSession(), "trail.owned_text")
                    : "[gold]" + trail.getPrice() + " " + Tr.t(ctx.getSession(), "trail.coins");

                card.add(UiBuilder.label("[white]" + trail.getName()).wrap().align("left").growX().padLeft(4f));
                card.add(UiBuilder.label(badge).align("right").padLeft(8f));
                card.row();

                String actionText = isCurrent ? Tr.t(ctx.getSession(), "trail.unequip") : owns ? Tr.t(ctx.getSession(), "trail.equip") : Tr.t(ctx.getSession(), "trail.buy");
                String trailAct = ctx.registerAction((sess, st, res) -> {
                    if (trail.owns(sess)) {
                        if (trail.getName().equals(sess.getData().trail)) {
                            sess.getData().trail = "";
                        } else {
                            sess.getData().trail = trail.getName();
                        }
                        if (sessionRepository != null) {
                            sessionRepository.markDirty(sess);
                        }
                    } else {
                        if (trailService.purchase(sess, trail)) {
                            sess.getData().trail = trail.getName();
                            sess.player.sendMessage(Tr.t(sess, "trail.purchased", "name", trail.getName()));
                        } else {
                            sess.player.sendMessage(Tr.t(sess, "trail.not_enough_coins", "name", trail.getName(), "coins", trail.getPrice()));
                        }
                    }
                    Registry.get(TrailMenu.class).send(sess, 0);
                });

                card.add(UiBuilder.button(actionText).clicked(trailAct).width(90f).height(48f).align("right").colspan(2).pad(2f));
                paneTable.add(card.growX().pad(2f)).row();
            }
        });
    }
}
