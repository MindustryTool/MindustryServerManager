package plugin.gamemode.catali.data;

import arc.func.Cons;
import arc.struct.Seq;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliConfig;

@Data
@NoArgsConstructor
public class CataliTeamData {
    public String leaderUuid;
    public Instant createdTime = Instant.now();
    public Instant lastLeaderOnlineTime = Instant.now();
    public Team team;
    public TeamLevel level;
    public TeamRespawn respawn;
    public TeamUpgrades upgrades;
    public Seq<String> members = new Seq<>();
    public String nextLeaderUuid;
    public Seq<String> joinRequests = new Seq<>();
    public boolean spawning = true;

    public CataliTeamData(Team team, String leaderUuid) {
        this.team = team;
        this.leaderUuid = leaderUuid;
        this.level = new TeamLevel();
        this.respawn = new TeamRespawn();
        this.upgrades = new TeamUpgrades();
        // Cores increase exp recv

        members.add(leaderUuid);
    }

    public void assignNextLeader(String uuid) {
        this.nextLeaderUuid = uuid;
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

    public Seq<Unit> getUpgradeableUnits() {
        var config = Registry.get(CataliConfig.class);
        return getTeamUnits().select(unit -> config.getUnitEvolutions(unit.type).size() > 0);
    }

    public boolean hasUnit() {
        return Groups.unit.find(unit -> unit.team == team && unit.isValid()) != null;
    }

    public Player getLeader() {
        return Groups.player.find(player -> player.uuid().equals(leaderUuid));
    }

    public void upgrade(CataliCommonUpgrade upgrade, int amount) {
        if (this.level.commonUpgradePoints < amount)
            return;

        this.level.commonUpgradePoints -= amount;
        var upgrades = this.upgrades;

        switch (upgrade) {
            case DAMAGE:
                upgrades.damageLevel += amount;
                upgrades.damageMultiplier += 0.1f * amount;
                break;
            case HEALTH:
                upgrades.healthLevel += amount;
                upgrades.healthMultiplier += 0.1f * amount;
                break;
            case HEALING:
                upgrades.regenLevel += amount;
                upgrades.regenMultiplier += 0.1f * amount;
                break;
            case EXPENRIENCE:
                upgrades.expLevel += amount;
                upgrades.expMultiplier += 0.1f * amount;
                break;
        }
    }
}
