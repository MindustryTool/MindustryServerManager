package plugin.gamemode.catali.event;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import plugin.gamemode.catali.data.CataliTeamData;

@Data
@RequiredArgsConstructor
public class TeamCreatedEvent {
    public final CataliTeamData team;
}
