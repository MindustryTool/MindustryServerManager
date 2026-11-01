package plugin.shop;

import mindustry.type.UnitType;
import plugin.session.Session;
import plugin.utils.Tr;

public class PetShopItem implements ShopItem {
    private final PetService petService;
    private final UnitType unitType;
    private final String id;
    private final long price;

    public PetShopItem(PetService petService, UnitType unitType, String id, long price) {
        this.petService = petService;
        this.unitType = unitType;
        this.id = id;
        this.price = price;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getTitle(Session session) {
        return Tr.t(session, "pet.summon_label", "name", unitType.name, "price", price);
    }

    @Override
    public String getDescription(Session session) {
        return Tr.t(session, "pet.spawned", "name", unitType.name);
    }

    @Override
    public long getPrice(Session session) {
        return price;
    }

    @Override
    public boolean isAvailable(Session session) {
        return session.player != null && petService.getRemainingCooldownSeconds(session) == 0;
    }

    @Override
    public boolean purchase(Session session) {
        return petService.summonPet(session, unitType);
    }
}
