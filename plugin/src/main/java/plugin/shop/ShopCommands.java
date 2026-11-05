package plugin.shop;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ShopCommands {

    private final ShopRegistry shopRegistry;
    private final ShopMenu shopMenu;
    private final GachaMenu gachaMenu;
    private final TaskMenu taskMenu;

    @ClientCommand(name = "shop", description = "Open server shop", admin = false)
    public void shop(Session session) {
        shopMenu.send(session, 0);
    }

    @ClientCommand(name = "gacha", description = "Open lucky gacha menu", admin = false)
    public void gacha(Session session) {
        gachaMenu.send(session, 0);
    }

    @ClientCommand(name = "tasks", description = "Open daily tasks menu", admin = false)
    public void tasks(Session session) {
        taskMenu.send(session, 0);
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

    @ClientCommand(name = "coins", description = "Check your coin balance", admin = false)
    public void coins(Session session) {
        money(session);
    }

    @ClientCommand(name = "buy", description = "Buy an item from shop directly", admin = false)
    public void buy(Session session, @Param(name = "itemId") String itemId) {
        if (itemId == null || itemId.trim().isEmpty()) {
            session.player.sendMessage(Tr.t(session, "shop.invalid_id"));
            return;
        }

        ShopItem item = shopRegistry.get(itemId);
        if (item == null) {
            session.player.sendMessage(Tr.t(session, "shop.item_not_found", "id", itemId));
            return;
        }

        shopRegistry.executePurchase(session, item);
    }
}
