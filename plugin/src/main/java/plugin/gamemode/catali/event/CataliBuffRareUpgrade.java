package plugin.gamemode.catali.event;

import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
import plugin.gamemode.catali.data.CataliTeamData;

public class CataliBuffRareUpgrade {
    public final CataliTeamData team;
    public final Unit unit;
    public final StatusEffect effect;

    public CataliBuffRareUpgrade(CataliTeamData team, Unit unit, StatusEffect effect) {
        this.team = team;
        this.unit = unit;
        this.effect = effect;
    }
}
