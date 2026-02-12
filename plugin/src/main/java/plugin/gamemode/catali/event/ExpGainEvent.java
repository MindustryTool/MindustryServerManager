package plugin.gamemode.catali.event;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import plugin.gamemode.catali.data.CataliTeamData;

@Data
@RequiredArgsConstructor
public class ExpGainEvent {
    public final CataliTeamData team;
    public final float amount;
    public final float x;
    public final float y;
}
