package plugin.gamemode.catali.data;

import arc.Core;
import arc.func.Cons;
import arc.struct.Seq;
import arc.util.Log;
import dto.Pair;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

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

@Data
@NoArgsConstructor
public class CataliTeamData {
    public String leaderUuid;
    public Instant createdTime = Instant.now();
    public Instant lastLeaderOnlineTime = Instant.now();

    public Team team;
    public TeamLevel level;
    public TeamUpgrades upgrades;
    public Seq<String> members = new Seq<>();
    public String nextLeaderUuid;
    public Seq<String> joinRequests = new Seq<>();
    public boolean spawning = true;
    public Seq<Pair<UnitType, Consumer<Unit>>> spawnQueue = new Seq<>();
    public Seq<RespawnEntry> respawn = new Seq<>();
    public Seq<Unit> despawnQueue = new Seq<>();

    public final int MAX_UNIT_COUNT = 10;

    public CataliTeamData(Team team, String leaderUuid) {
        this.team = team;
        this.leaderUuid = leaderUuid;
        this.level = new TeamLevel();
        this.upgrades = new TeamUpgrades();
        // Cores increase exp recv

        members.add(leaderUuid);
    }

    public void addRespawnUnit(UnitType type, Duration duration) {
        respawn.add(new RespawnEntry(type, duration));
    }

    public Seq<RespawnEntry> getRespawnReadyUnit() {
        var needRespawn = respawn.select(entry -> entry.respawnAt.isBefore(Instant.now()));

        respawn.removeAll(needRespawn);

        return needRespawn;
    }

    public void assignNextLeader(String uuid) {
        this.nextLeaderUuid = uuid;
    }

    public boolean isTimeout() {
        return Instant.now().isAfter(lastLeaderOnlineTime.plusSeconds(60 * 2));
    }

    public void refreshTimeout() {
        lastLeaderOnlineTime = Instant.now();
    }

    public void eachMember(Cons<Player> cons) {
        Groups.player.each(player -> {
            if (player.team() == team) {
                cons.get(player);
            }
        });
    }

    public Seq<Unit> units() {
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

        return units().select(unit -> config.getUnitEvolutions(unit.type).size() > 0 && !despawnQueue.contains(unit));
    }

    public boolean hasUnit() {
        return Groups.unit.find(unit -> unit.team == team && unit.isValid()) != null;
    }

    public Player leader() {
        return Groups.player.find(player -> player.uuid().equals(leaderUuid));
    }

    public String name() {
        var leader = leader();
        var teamName = leader != null ? leader.name : String.valueOf(team.id);

        return teamName;
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
            case REGEN:
                upgrades.levelUpRegen(amount);
                break;
            case EXP:
                upgrades.levelUpExp(amount);
                break;
        }

        PluginEvents.fire(new TeamUpgradeChangedEvent(this));
    }

    public synchronized boolean upgradeUnit(UnitType type, Unit unit, Consumer<Unit> callback) {
        despawnQueue.add(unit);
        spawnQueue.add(Pair.of(type, (unitSpawned) -> {
            despawnQueue.remove(unit);
            callback.accept(unitSpawned);
            unit.kill();
        }));
        return true;
    }

    public synchronized boolean spawnUnit(UnitType type, Consumer<Unit> callback) {
        if (!canHaveMoreUnit()) {
            return false;
        }

        spawnQueue.add(Pair.of(type, callback));
        return true;
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

        var spawnX = -1;
        var spawnY = -1;

        if (leaderUnit != null) {
            spawnX = leaderUnit.tileX();
            spawnY = leaderUnit.tileY();
        } else {
            var units = units();

            if (units.isEmpty()) {
                return false;
            }

            for (var unit : units) {
                spawnX += unit.tileX();
                spawnY += unit.tileY();
            }

            spawnX /= units.size;
            spawnY /= units.size;
        }

        if (spawnX < 0 || spawnY < 0) {
            return false;
        }

        Tile safeTile = null;
        Tile tile = null;
        int maxSearchRange = 5;
        // Search for safe tile arround

        for (int i = 1; i < maxSearchRange; i++) {
            for (int x = 1 - i; x < i; x++) {
                tile = Vars.world.tile(spawnX + x, spawnY - i);
                if (SpawnerHelper.isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
                tile = Vars.world.tile(spawnX + x, spawnY + i);
                if (SpawnerHelper.isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
            }

            for (int y = 1 - i; y < i; y++) {
                tile = Vars.world.tile(spawnX - i, spawnY + y);
                if (SpawnerHelper.isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
                tile = Vars.world.tile(spawnX + i, spawnY + y);
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
        return units().size + spawnQueue.size + respawn.size < MAX_UNIT_COUNT;
    }
}
