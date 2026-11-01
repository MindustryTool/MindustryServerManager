package plugin.shop;

import mindustry.gen.Iconc;
import plugin.core.Registry;
import plugin.menus.PluginMenu;
import plugin.session.Session;
import plugin.utils.Tr;

public class GachaMenu extends PluginMenu<Integer> {

    @Override
    public void build(Session session, Integer state) {
        this.title = Tr.t(session, "gacha.title", "coins", session.getData().money);
        this.description = Tr.t(session, "gacha.description");

        option(Tr.t(session, "gacha.roll_single_btn", "cost", GachaService.COST_SINGLE), (s, p) -> {
            GachaService gachaService = Registry.getOrNull(GachaService.class);
            if (gachaService != null) {
                gachaService.rollSingle(s);
            }
            this.send(s, 0);
        });

        option(Tr.t(session, "gacha.roll_ten_btn", "cost", GachaService.COST_MULTI_10), (s, p) -> {
            GachaService gachaService = Registry.getOrNull(GachaService.class);
            if (gachaService != null) {
                gachaService.rollTen(s);
            }
            this.send(s, 0);
        });
        row();

        option("[lightgray]" + Iconc.left + " " + Tr.t(session, "gacha.back_to_shop"), (s, p) -> new ShopMenu().send(s, 0));
        row();

        text(Tr.t(session, "gacha.close"));
    }
}
