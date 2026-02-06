package plugin.gamemode.catali.data;

import arc.func.Cons;
import arc.struct.Seq;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;

public class CataliTeamData {
    public Team team;
    public TeamMetadata metadata;
    public TeamLevel level;
    public TeamRespawn respawn;
    public TeamUpgrades upgrades;
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
