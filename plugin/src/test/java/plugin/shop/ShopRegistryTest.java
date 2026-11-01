package plugin.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import plugin.session.Session;

public class ShopRegistryTest {

    private static class DummyShopItem implements ShopItem {
        private final String id;

        DummyShopItem(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getTitle(Session session) {
            return id;
        }

        @Override
        public String getDescription(Session session) {
            return id;
        }

        @Override
        public long getPrice(Session session) {
            return 100;
        }

        @Override
        public boolean isAvailable(Session session) {
            return true;
        }

        @Override
        public boolean purchase(Session session) {
            return true;
        }
    }

    @Test
    void registerAndGetItem() {
        ShopRegistry registry = new ShopRegistry(null, null, null);
        DummyShopItem item = new DummyShopItem("sword");
        registry.register(item);

        assertEquals(item, registry.get("sword"));
        assertEquals(item, registry.get("SWORD"));
    }

    @Test
    void upgradeAliasResolvesToCore() {
        ShopRegistry registry = new ShopRegistry(null, null, null);
        DummyShopItem coreItem = new DummyShopItem("core");
        registry.register(coreItem);

        assertEquals(coreItem, registry.get("core"));
        assertEquals(coreItem, registry.get("upgrade"));
    }

    @Test
    void unregisteredItemReturnsNull() {
        ShopRegistry registry = new ShopRegistry(null, null, null);
        assertNull(registry.get("nonexistent"));
        assertNull(registry.get(null));
    }

    @Test
    void registerNullThrowsException() {
        ShopRegistry registry = new ShopRegistry(null, null, null);
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void getItemsReturnsAllRegistered() {
        ShopRegistry registry = new ShopRegistry(null, null, null);
        registry.register(new DummyShopItem("item1"));
        registry.register(new DummyShopItem("item2"));

        assertNotNull(registry.getItems());
        assertTrue(registry.getItems().size() >= 2);
    }

    @Test
    void purchaseCooldownIsConfigured() {
        assertEquals(400L, ShopRegistry.PURCHASE_COOLDOWN_MILLIS);
    }
}
