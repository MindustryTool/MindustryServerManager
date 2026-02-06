package plugin.gamemode.catali.data;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TeamMetadata {
    public int teamId;
    public String leaderUuid;
    public long createdTime;
    public long lastLeaderOnlineTime;

    public TeamMetadata(int teamId, String leaderUuid) {
        this.teamId = teamId;
        this.leaderUuid = leaderUuid;
        this.createdTime = System.currentTimeMillis();
        this.lastLeaderOnlineTime = System.currentTimeMillis();
    }
}
