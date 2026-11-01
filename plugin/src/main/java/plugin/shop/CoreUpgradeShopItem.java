package plugin.shop;

import plugin.session.Session;
import plugin.utils.Tr;

public class CoreUpgradeShopItem implements ShopItem {
    private final CoreUnitUpgradeService upgradeService;

    public CoreUpgradeShopItem(CoreUnitUpgradeService upgradeService) {
        this.upgradeService = upgradeService;
    }

    @Override
    public String getId() {
        return "core";
    }

    @Override
    public String getTitle(Session session) {
        int current = session.getData().coreUpgradeLevel;
        if (current >= CoreUnitUpgradeService.MAX_TIER) {
            return Tr.t(session, "shop.core_max_tier", "tier", current);
        }
        int next = current + 1;
        long cost = upgradeService.getUpgradeCost(next);
        return Tr.t(session, "shop.core_upgrade_label", "tier", next, "cost", cost);
    }

    @Override
    public String getDescription(Session session) {
        int next = session.getData().coreUpgradeLevel + 1;
        return Tr.t(session, "shop.core_success", "tier", next);
    }

    @Override
    public long getPrice(Session session) {
        int next = session.getData().coreUpgradeLevel + 1;
        return upgradeService.getUpgradeCost(next);
    }

    @Override
    public boolean isAvailable(Session session) {
        return session.getData().coreUpgradeLevel < CoreUnitUpgradeService.MAX_TIER;
    }

    @Override
    public boolean purchase(Session session) {
        return upgradeService.upgrade(session);
    }
}
