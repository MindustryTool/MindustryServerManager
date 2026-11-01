package plugin.shop;

import plugin.session.Session;

public interface ShopItem {
    String getId();

    String getTitle(Session session);

    String getDescription(Session session);

    long getPrice(Session session);

    boolean isAvailable(Session session);

    boolean purchase(Session session);
}
