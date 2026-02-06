package plugin.gamemode.catali.data;

import arc.func.Cons;
import arc.struct.Seq;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import plugin.json.TeamDeserializer;
import plugin.json.TeamSerializer;

@Data
@NoArgsConstructor
public class CataliTeamData {
    @JsonSerialize(using = TeamSerializer.class)
    @JsonDeserialize(using = TeamDeserializer.class)
    public String leaderUuid;
    public Instant createdTime = Instant.now();
    public Instant lastLeaderOnlineTime = Instant.now();
    public Team team;
    public TeamLevel level;
    public TeamRespawn respawn;
    public TeamUpgrades upgrades;
    public Seq<String> members = new Seq<>();
    // Cores increase exp recv

    public boolean spawning = true;

    public CataliTeamData(Team team, String leaderUuid) {
        this.team = team;
        this.leaderUuid = leaderUuid;
        this.level = new TeamLevel();
        this.respawn = new TeamRespawn();
        this.upgrades = new TeamUpgrades();

        members.add(leaderUuid);
    }

    public Instant timeoutAt() {
        return lastLeaderOnlineTime.plusSeconds(60 * 2);
    }

    public void eachMember(Cons<Player> cons) {
        Groups.player.each(player -> {
            if (player.team() == team) {
                cons.get(player);
            }
        });
    }

    public Seq<Unit> getTeamUnits() {
        Seq<Unit> units = new Seq<>();
        for (var unit : Groups.unit) {
            if (unit.team == team && unit.isValid()) {
                units.add(unit);
            }
        }

        return units;
    }

    public boolean hasUnit() {
        return Groups.unit.find(unit -> unit.team == team && unit.isValid()) != null;
    }
}
