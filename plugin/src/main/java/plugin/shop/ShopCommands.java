package plugin.shop;

import mindustry.Vars;
import plugin.Cfg;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Param;
import plugin.dailytask.TaskMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@ConditionOn(Cfg.OnOfficial.class)
public class ShopCommands {
    private final ShopRegistry shopRegistry;

    public ShopCommands(ShopRegistry shopRegistry) {
        this.shopRegistry = shopRegistry;
    }

    @ClientCommand(name = "shop", description = "Open server shop", admin = false)
    public void shop(Session session) {
        new ShopMenu().send(session, 0);
    }

    @ClientCommand(name = "gacha", description = "Open lucky gacha menu", admin = false)
    public void gacha(Session session) {
        new GachaMenu().send(session, 0);
    }

    @ClientCommand(name = "tasks", description = "Open daily tasks menu", admin = false)
    public void tasks(Session session) {
        new TaskMenu().send(session, 0);
    }

    @ClientCommand(name = "money", description = "Check your coin balance", admin = false)
    public void money(Session session) {
        if (session.player != null && Vars.net != null) {
            session.player.sendMessage(Tr.t(session, "money.balance", "coins", session.getData().money));
        }
    }

    @ClientCommand(name = "balance", description = "Check your coin balance", admin = false)
    public void balance(Session session) {
        money(session);
    }

    @ClientCommand(name = "buy", description = "Purchase shop items directly: /buy <item>", admin = false)
    public void buy(Session session, @Param(name = "item") String item) {
        if (item == null || item.isEmpty()) {
            shop(session);
            return;
        }

        ShopItem target = shopRegistry.get(item.trim());
        if (target != null) {
            shopRegistry.executePurchase(session, target);
        } else {
            String available = String.join(", ", shopRegistry.getItems().stream().map(ShopItem::getId).toList());
            if (session.player != null && Vars.net != null) {
                session.player.sendMessage(Tr.t(session, "shop.unknown_item", "item", item, "available", available));
            }
        }
    }
}
