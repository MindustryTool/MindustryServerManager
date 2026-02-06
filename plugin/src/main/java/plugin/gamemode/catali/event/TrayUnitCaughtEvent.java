package plugin.gamemode.catali.event;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import mindustry.type.UnitType;
import plugin.gamemode.catali.data.CataliTeamData;

@Data
@RequiredArgsConstructor
public class TrayUnitCaughtEvent {
    public final CataliTeamData team;
    public final UnitType type;
}
