package plugin.shop;

import plugin.session.Session;
import plugin.utils.Tr;

public class GachaShopItem implements ShopItem {
    private final GachaService gachaService;

    public GachaShopItem(GachaService gachaService) {
        this.gachaService = gachaService;
    }

    @Override
    public String getId() {
        return "gacha";
    }

    @Override
    public String getTitle(Session session) {
        return Tr.t(session, "gacha.roll_single_btn", "cost", GachaService.COST_SINGLE);
    }

    @Override
    public String getDescription(Session session) {
        return Tr.t(session, "gacha.description");
    }

    @Override
    public long getPrice(Session session) {
        return GachaService.COST_SINGLE;
    }

    @Override
    public boolean isAvailable(Session session) {
        return true;
    }

    @Override
    public boolean purchase(Session session) {
        return gachaService.rollSingle(session) != null;
    }
}
