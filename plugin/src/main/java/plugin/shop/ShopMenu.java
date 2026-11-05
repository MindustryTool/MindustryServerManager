package plugin.shop;

import java.util.List;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.trail.TrailMenu;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class ShopMenu extends ServerMenu<Integer> {

    private final ShopRegistry shopRegistry;

    @Override
    public void build(MenuContext<Integer> ctx) {
        this.title = Tr.t(ctx.getSession(), "shop.title", "coins", ctx.getSession().getData().money);

        ctx.label("[accent]" + Tr.t(ctx.getSession(), "shop.description"));
        ctx.row();

        if (shopRegistry != null) {
            List<ShopItem> items = shopRegistry.getItems();

            ctx.pane(MenuContext.SAFE_WIDTH, 300f, paneTable -> {
                for (ShopItem item : items) {
                    if ("gacha".equals(item.getId())) {
                        continue;
                    }

                    TableBuilder card = UiBuilder.table().pad(4f);
                    card.add(UiBuilder.label("[gold]" + item.getTitle(ctx.getSession())).align("left"));

                    String buyAct = ctx.registerAction((sess, st, res) -> {
                        shopRegistry.executePurchase(sess, item);
                        Registry.get(ShopMenu.class).send(sess, 0);
                    });

                    card.add(UiBuilder.button("Buy").clicked(buyAct).width(90f).height(38f).align("right").padLeft(8f));
                    paneTable.add(card.growX().pad(2f)).row();
                }
            });
            ctx.row();
        }

        ctx.table(nav -> {
            String gachaAct = ctx.registerAction((sess, st, res) -> Registry.get(GachaMenu.class).send(sess, 0));
            String trailAct = ctx.registerAction((sess, st, res) -> Registry.get(TrailMenu.class).send(sess, 0));
            String closeAct = ctx.registerAction((sess, st, res) -> {});

            nav.add(UiBuilder.button("[gold]" + Iconc.star + " " + Tr.t(ctx.getSession(), "shop.open_gacha")).clicked(gachaAct).width(135f).height(40f).pad(3f));
            nav.add(UiBuilder.button("[sky]" + Iconc.star + " " + Tr.t(ctx.getSession(), "shop.open_trails")).clicked(trailAct).width(135f).height(40f).pad(3f));
            nav.row();
            nav.add(UiBuilder.button(Tr.t(ctx.getSession(), "shop.close")).clicked(closeAct).width(120f).height(38f).colspan(2).pad(3f));
        });
    }
}
