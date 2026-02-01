package plugin.event;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Player;
import mindustry.gen.Unit;

@Data
@RequiredArgsConstructor
public class PlayerKillUnitEvent {
    private final Player player;
    private final Unit unit;
}
