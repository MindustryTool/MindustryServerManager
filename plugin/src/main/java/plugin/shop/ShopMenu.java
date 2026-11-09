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
            List<ShopItem> items = shopRegistry.getItems().stream()
                .filter(item -> !"gacha".equals(item.getId()))
                .toList();

            ctx.pane(260f, paneTable -> {
                if (items.isEmpty()) {
                    paneTable.add(UiBuilder.label(Tr.t(ctx.getSession(), "shop.no_items"))
                        .wrap().align("left").growX().pad(8f)).row();
                    return;
                }

                for (ShopItem item : items) {
                    TableBuilder card = UiBuilder.table().align("left");
                    card.width(ctx.safeCardWidth());
                    card.add(UiBuilder.label("[gold]" + item.getTitle(ctx.getSession())).wrap().align("left").growX().padLeft(4f));

                    String buyAct = ctx.registerAction((sess, st, res) -> {
                        shopRegistry.executePurchase(sess, item);
                        Registry.get(ShopMenu.class).send(sess, 0);
                    });

                    card.add(UiBuilder.button(Tr.t(ctx.getSession(), "shop.buy")).clicked(buyAct).width(80f).height(48f).align("right").padLeft(6f));
                    paneTable.add(card.growX().pad(2f)).row();
                }
            });
            ctx.row();
        }

        ctx.table(nav -> {
            nav.width(ctx.safeWidth()).align("center");
            String gachaAct = ctx.registerAction((sess, st, res) -> Registry.get(GachaMenu.class).send(sess, 0));
            String trailAct = ctx.registerAction((sess, st, res) -> Registry.get(TrailMenu.class).send(sess, 0));

            nav.add(UiBuilder.button("[gold]" + Iconc.star + " " + Tr.t(ctx.getSession(), "shop.open_gacha")).clicked(gachaAct).height(38f).growX().pad(2f));
            nav.add(UiBuilder.button("[sky]" + Iconc.star + " " + Tr.t(ctx.getSession(), "shop.open_trails")).clicked(trailAct).height(38f).growX().pad(2f));
        });
    }
}
