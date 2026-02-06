package plugin.gamemode.catali.data;

import java.util.HashSet;
import java.util.Set;

public class TeamMetadata {
    public int teamId;
    public String leaderUuid;
    public Set<String> members = new HashSet<>();
    public long createdTime;
    public long lastLeaderOnlineTime;

    public TeamMetadata(int teamId, String leaderUuid) {
        this.teamId = teamId;
        this.leaderUuid = leaderUuid;
        this.members.add(leaderUuid);
        this.createdTime = System.currentTimeMillis();
        this.lastLeaderOnlineTime = System.currentTimeMillis();
    }
}
