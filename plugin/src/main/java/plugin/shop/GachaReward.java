package plugin.shop;

import plugin.session.Session;

@FunctionalInterface
public interface GachaReward {
    String apply(Session session);
}
