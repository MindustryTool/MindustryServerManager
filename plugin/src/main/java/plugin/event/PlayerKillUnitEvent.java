package plugin.event;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Player;
import mindustry.type.UnitType;

@Data
@RequiredArgsConstructor
public class PlayerKillUnitEvent {
    private final Player player;
    private final UnitType unitType;
}
