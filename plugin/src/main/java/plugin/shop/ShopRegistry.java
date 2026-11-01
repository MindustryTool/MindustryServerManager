package plugin.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mindustry.content.UnitTypes;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.session.Session;

@Component
@ConditionOn(Cfg.OnOfficial.class)
public class ShopRegistry {
    public static final long PURCHASE_COOLDOWN_MILLIS = 400L;

    private final Map<String, ShopItem> items = new LinkedHashMap<>();
    private final Map<String, Long> lastActionTime = new ConcurrentHashMap<>();

    public ShopRegistry(PetService petService, CoreUnitUpgradeService upgradeService, GachaService gachaService) {
        register(new PetShopItem(petService, UnitTypes.poly, "poly", PetService.COST_POLY));
        register(new PetShopItem(petService, UnitTypes.mega, "mega", PetService.COST_MEGA));
        register(new CoreUpgradeShopItem(upgradeService));
        register(new GachaShopItem(gachaService));
    }

    public boolean canAct(Session session) {
        if (session == null || session.player == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long last = lastActionTime.getOrDefault(session.player.uuid(), 0L);
        if (now - last < PURCHASE_COOLDOWN_MILLIS) {
            return false;
        }
        lastActionTime.put(session.player.uuid(), now);
        return true;
    }

    public boolean executePurchase(Session session, ShopItem item) {
        if (item == null || !canAct(session)) {
            return false;
        }
        synchronized (session.getData()) {
            return item.purchase(session);
        }
    }

    public synchronized void register(ShopItem item) {
        if (item == null) {
            throw new IllegalArgumentException("ShopItem cannot be null");
        }
        items.put(item.getId().toLowerCase(), item);
    }

    public synchronized ShopItem get(String id) {
        if (id == null) {
            return null;
        }
        String key = id.toLowerCase();
        if ("upgrade".equals(key)) {
            key = "core";
        }
        return items.get(key);
    }

    public synchronized List<ShopItem> getItems() {
        return Collections.unmodifiableList(new ArrayList<>(items.values()));
    }
}
