package plugin.gamemode.catali.data;

import arc.Core;
import arc.func.Cons;
import arc.struct.Seq;
import arc.util.Log;
import dto.Pair;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import plugin.PluginEvents;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.event.TeamUpgradeChangedEvent;
import plugin.gamemode.catali.spawner.SpawnerHelper;
import plugin.json.TeamDeserializer;
import plugin.json.TeamSerializer;

@Data
@NoArgsConstructor
public class CataliTeamData {
    public String leaderUuid;
    public Instant createdTime = Instant.now();
    public Instant lastLeaderOnlineTime = Instant.now();

    @JsonSerialize(using = TeamSerializer.class)
    @JsonDeserialize(using = TeamDeserializer.class)
    public Team team;
    public TeamLevel level;
    public TeamRespawn respawn;
    public TeamUpgrades upgrades;
    public Seq<String> members = new Seq<>();
    public String nextLeaderUuid;
    public Seq<String> joinRequests = new Seq<>();
    public boolean spawning = true;
    public Seq<Pair<UnitType, Consumer<Unit>>> spawnQueue = new Seq<>();
    public final int MAX_UNIT_COUNT = 10;

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

    public void consumeUpgrade(CataliCommonUpgrade upgrade, int amount) {
        if (this.level.commonUpgradePoints < amount) {
            return;
        }

        this.level.commonUpgradePoints -= amount;
        var upgrades = this.upgrades;

        switch (upgrade) {
            case DAMAGE:
                upgrades.levelUpDamage(amount);
                break;
            case HEALTH:
                upgrades.levelUpHealth(amount);
                break;
            case HEALING:
                upgrades.levelUpHealing(amount);
                break;
            case EXPENRIENCE:
                upgrades.levelUpExp(amount);
                break;
        }

        PluginEvents.fire(new TeamUpgradeChangedEvent(this));
    }

    public void spawnUnit(UnitType type, Consumer<Unit> callback) {
        spawnQueue.add(Pair.of(type, callback));
    }

    public boolean attempToSpawn() {
        var first = spawnQueue.firstOpt();

        if (first == null) {
            return false;
        }

        var type = first.first;
        var callback = first.second;

        var leaderPlayer = Groups.player.find(p -> p.uuid().equals(leaderUuid));

        if (leaderPlayer == null) {
            return false;
        }

        Unit leaderUnit = Groups.unit.find(u -> u == leaderPlayer.unit());

        if (leaderUnit == null) {
            leaderUnit = getTeamUnits().firstOpt();
        }

        if (leaderUnit == null) {
            Log.info("No leader unit for player @", leaderPlayer.name);
            return false;
        }

        Tile safeTile = null;
        Tile tile = null;
        int maxSearchRange = 10;
        // Search for safe tile arround

        for (int i = 1; i < maxSearchRange; i++) {
            for (int x = 1 - i; x < i; x++) {
                tile = Vars.world.tile(leaderUnit.tileX() + x, leaderUnit.tileY() - i);
                if (SpawnerHelper.isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
                tile = Vars.world.tile(leaderUnit.tileX() + x, leaderUnit.tileY() + i);
                if (SpawnerHelper.isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
            }

            for (int y = 1 - i; y < i; y++) {
                tile = Vars.world.tile(leaderUnit.tileX() - i, leaderUnit.tileY() + y);
                if (SpawnerHelper.isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
                tile = Vars.world.tile(leaderUnit.tileX() + i, leaderUnit.tileY() + y);
                if (SpawnerHelper.isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
            }
        }

        if (safeTile == null) {
            Log.info("No safe tile found for team @", team);
            return false;
        }

        Unit unit = type.create(team);
        unit.set(safeTile.worldx(), safeTile.worldy());
        upgrades.apply(unit);

        Core.app.post(() -> {
            unit.add();
            callback.accept(unit);
            spawnQueue.remove(first);
        });

        return true;
    }

    public boolean canHaveMoreUnit() {
        return getTeamUnits().size + spawnQueue.size + respawn.respawn.size < MAX_UNIT_COUNT;
    }
}
