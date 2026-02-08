package plugin.gamemode.catali.event;

import lombok.Data;
import plugin.gamemode.catali.data.CataliTeamData;

@Data
public class TeamUpgradeChangedEvent {
    public final CataliTeamData team;
}
