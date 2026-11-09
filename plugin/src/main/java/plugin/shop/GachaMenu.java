package plugin.shop;

import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class GachaMenu extends ServerMenu<Integer> {

    private final GachaService gachaService;

    @Override
    public void build(MenuContext<Integer> ctx) {
        this.title = Tr.t(ctx.getSession(), "gacha.title", "coins", ctx.getSession().getData().money);

        ctx.label(Tr.t(ctx.getSession(), "gacha.machine_title"));
        ctx.row();
        ctx.label("[lightgray]" + Tr.t(ctx.getSession(), "gacha.description"));
        ctx.row();

        ctx.table(rolls -> {
            String singleAct = ctx.registerAction((sess, st, res) -> {
                if (gachaService != null) {
                    gachaService.rollSingle(sess);
                }
                Registry.get(GachaMenu.class).send(sess, 0);
            });

            String tenAct = ctx.registerAction((sess, st, res) -> {
                if (gachaService != null) {
                    gachaService.rollTen(sess);
                }
                Registry.get(GachaMenu.class).send(sess, 0);
            });

            rolls.add(UiBuilder.button(Tr.t(ctx.getSession(), "gacha.roll_single_btn", "cost", GachaService.COST_SINGLE)).clicked(singleAct).growX().height(48f).pad(3f)).row();
            rolls.add(UiBuilder.button(Tr.t(ctx.getSession(), "gacha.roll_ten_btn", "cost", GachaService.COST_MULTI_10)).clicked(tenAct).growX().height(48f).pad(3f));
        }).growX();

        ctx.row();

        ctx.table(nav -> {
            String backAct = ctx.registerAction((sess, st, res) -> Registry.get(ShopMenu.class).send(sess, 0));
            nav.add(UiBuilder.button("[lightgray]" + Iconc.left + " " + Tr.t(ctx.getSession(), "gacha.back_to_shop")).clicked(backAct).width(160f).height(48f).pad(3f));
        });
    }
}
