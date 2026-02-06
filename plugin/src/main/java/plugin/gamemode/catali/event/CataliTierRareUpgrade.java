package plugin.gamemode.catali.event;

import mindustry.gen.Unit;
import mindustry.type.UnitType;
import plugin.gamemode.catali.data.CataliTeamData;

public class CataliTierRareUpgrade {
    public final CataliTeamData team;
    public final Unit unit;
    public final UnitType upgradeTo;

    public CataliTierRareUpgrade(CataliTeamData team, Unit unit, UnitType upgradeTo) {
        this.team = team;
        this.unit = unit;
        this.upgradeTo = upgradeTo;
    }
}
