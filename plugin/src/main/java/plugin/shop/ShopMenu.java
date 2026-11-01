package plugin.shop;

import java.util.List;
import mindustry.gen.Iconc;
import plugin.core.Registry;
import plugin.menus.PluginMenu;
import plugin.session.Session;
import plugin.trail.TrailMenu;
import plugin.utils.Tr;

public class ShopMenu extends PluginMenu<Integer> {

    @Override
    public void build(Session session, Integer state) {
        this.title = Tr.t(session, "shop.title", "coins", session.getData().money);
        this.description = Tr.t(session, "shop.description");

        ShopRegistry registry = Registry.getOrNull(ShopRegistry.class);
        if (registry != null) {
            List<ShopItem> items = registry.getItems();
            int count = 0;
            for (ShopItem item : items) {
                if ("gacha".equals(item.getId())) {
                    continue;
                }
                option(item.getTitle(session), (s, p) -> {
                    registry.executePurchase(s, item);
                    this.send(s, 0);
                });
                count++;
                if (count % 2 == 0) {
                    row();
                }
            }
            if (count % 2 != 0) {
                row();
            }
        }

        option("[gold]" + Iconc.star + " " + Tr.t(session, "shop.open_gacha"), (s, p) -> new GachaMenu().send(s, 0));
        option("[sky]" + Iconc.star + " " + Tr.t(session, "shop.open_trails"), (s, p) -> new TrailMenu().send(s, 0));
        row();

        text(Tr.t(session, "shop.close"));
    }
}
