package plugin.gamemode.catali.data;

import arc.func.Cons;
import arc.struct.Seq;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import plugin.json.UnitDeserializer;
import plugin.json.UnitSerializer;
import plugin.json.TeamDeserializer;
import plugin.json.TeamSerializer;

@Data
@NoArgsConstructor
public class CataliTeamData {
    @JsonSerialize(using = TeamSerializer.class)
    @JsonDeserialize(using = TeamDeserializer.class)
    public Team team;
    public TeamMetadata metadata;
    public TeamLevel level;
    public TeamRespawn respawn;
    public TeamUpgrades upgrades;
    @JsonSerialize(contentUsing = UnitSerializer.class)
    @JsonDeserialize(contentUsing = UnitDeserializer.class)
    public Seq<Unit> units = new Seq<>();

    public boolean spawning = true;

    public CataliTeamData(Team team, String leaderUuid) {
        this.team = team;
        this.metadata = new TeamMetadata(team.id, leaderUuid);
        this.level = new TeamLevel();
        this.respawn = new TeamRespawn();
        this.upgrades = new TeamUpgrades();
    }

    public void eachMember(Cons<Player> cons) {
        Groups.player.each(player -> {
            if (player.team() == team) {
                cons.get(player);
            }
        });
    }

}
