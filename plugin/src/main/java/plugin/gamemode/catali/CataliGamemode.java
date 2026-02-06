package plugin.gamemode.catali;

import arc.Core;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.EventType.*;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import plugin.Control;
import plugin.PluginEvents;
import plugin.annotations.Gamemode;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Persistence;
import plugin.event.SessionCreatedEvent;
import plugin.gamemode.catali.data.*;
import plugin.gamemode.catali.spawner.BlockSpawner;
import plugin.gamemode.catali.spawner.UnitSpawner;
import plugin.service.I18n;
import plugin.utils.TimeUtils;
import plugin.utils.Utils;

import static mindustry.content.UnitTypes.poly;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Gamemode("catali")
@RequiredArgsConstructor
public class CataliGamemode {

    @Persistence("catali-teams.json")
    private final Seq<CataliTeamData> teams = new Seq<>();

    private final UnitSpawner unitSpawner;
    private final BlockSpawner blockSpawner;
    private final CataliConfig config;

    private final Seq<UnitType> coreUnits = Seq.with(UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma, UnitTypes.evoke,
            UnitTypes.incite, UnitTypes.emanate);

    private final Team SPECTATOR_TEAM = Team.get(255);

    @Init
    public void init() {
        Vars.content.units().forEach(u -> u.flying = u.naval ? true : u.flying);

        UnitTypes.omura.abilities.clear();
        UnitTypes.omura.weapons.get(0).bullet.damage /= 2;

        UnitTypes.collaris.speed /= 2;

        for (var block : Vars.content.blocks()) {
            Vars.state.rules.bannedBlocks.add(block);
        }

        Vars.state.rules.canGameOver = false;

        Control.SCHEDULER.scheduleWithFixedDelay(this::update, 0, 2, TimeUnit.SECONDS);
        Control.SCHEDULER.scheduleWithFixedDelay(this::spawn, 0, 2, TimeUnit.SECONDS);

        Log.info(config);
        Log.info("[accent]Cataio gamemode loaded");
    }

    public CataliTeamData findTeam(Player player) {
        return teams.find(team -> team.members.contains(player.uuid()));
    }

    public void spawn() {
        Log.info("Spawning units and blocks");
        unitSpawner.spawn(Vars.state.rules.waveTeam);
        Log.info("Units spawned");
        blockSpawner.spawn(Vars.state.rules.waveTeam);
        Log.info("Blocks spawned");
    }

    public void update() {
        if (!Vars.state.isGame()) {
            return;
        }

        Core.app.post(() -> {
            updateRespawn();
            updateTeam();
            updatePlayer();
        });
    }

    private void updatePlayer() {
        for (var player : Groups.player) {
            var team = findTeam(player);

            if (team == null) {
                Call.infoPopup(player.con, I18n.t(player, "@User", "[accent]/play[]", "@to start a new team"), 2,
                        Align.center, 0, 0, 30, 0);
            }

            if (player.unit() != null && coreUnits.contains(player.unit().type)) {
                player.team(Team.get(255));
                player.unit().kill();
            }
        }
    }

    private void updateRespawn() {
        for (CataliTeamData data : teams) {
            var respawns = data.respawn.getRespawnUnit();
            for (var entry : respawns) {
                var unit = spawnUnitForTeam(data, entry.type);

                if (unit == null) {
                    data.respawn.addUnit(entry.type, Duration.ofSeconds(1));
                }
            }
        }
    }

    private void updateTeam() {
        Seq<CataliTeamData> remove = new Seq<>();

        for (var team : teams) {
            if (!team.hasUnit() && Instant.now().isAfter(team.timeoutAt())) {
                remove.add(team);
            } else if (team.spawning == true) {
                var leader = Groups.player.find(player -> player.uuid().equals(team.leaderUuid));

                if (leader == null) {
                    continue;
                }

                Call.infoPopup(leader.con, I18n.t(leader, "@Tap to spawn"), 2, Align.center, 0, 0, 0, 0);
            }
        }

        for (var team : remove) {
            PluginEvents.fire(new TeamFallenEvent(team));
        }
    }

    @Listener
    public void onTeamFallen(TeamFallenEvent event) {
        teams.remove(event.team);

        Utils.forEachPlayerLocale((locale, players) -> {
            String message = I18n.t(locale, "[scarlet]", "@Team", event.team.team.id, "@has been eliminated!");
            for (var player : players) {
                player.sendMessage(message);
            }
        });
    }

    @Listener
    public void worldLoadEvent(WorldLoadBeginEvent event) {
        teams.clear();
    }

    @Listener
    public void onPlayerJoin(SessionCreatedEvent event) {
        var session = event.session;
        var player = session.player;

        if (player.unit() != null) {
            player.unit().kill();
        }

        var playerTeam = teams.find(team -> team.leaderUuid.equals(player.uuid()));

        if (playerTeam != null) {
            player.team(playerTeam.team);

            if (playerTeam.hasUnit()) {
                assignUnitForPlayer(playerTeam, player);
            } else {
                PluginEvents.fire(new TeamFallenEvent(playerTeam));
            }
        } else {
            player.team(SPECTATOR_TEAM);
            player.sendMessage("[yellow]Type /play to start a new team!");
        }
    }

    @Listener
    public void onTap(TapEvent event) {
        var playerTeam = findTeam(event.player);

        if (playerTeam != null && playerTeam.spawning == true) {
            var spawnX = event.tile.worldx();
            var spawnY = event.tile.worldy();

            var spawnable = isTileSafe(event.tile, UnitTypes.poly);

            if (spawnable) {
                Unit starter = UnitTypes.poly.create(playerTeam.team);

                
                starter.set(spawnX, spawnY);
                starter.add();

                event.player.unit(starter);

                playerTeam.spawning = false;
            } else {
                Call.infoPopup(event.player.con, I18n.t(event.player, "[scarlet]", "@Tile is not safe to spawn"),
                        5, Align.center, 5, 5, 5, 5);
            }
        }
    }

    @Listener
    public void onUnitDestroy(UnitDestroyEvent e) {
        if (coreUnits.contains(e.unit.type)) {
            return;
        }

        CataliTeamData victimTeam = teams.find(team -> team.team.id == e.unit.team.id);

        if (victimTeam != null) {
            if (!victimTeam.hasUnit()) {
                PluginEvents.fire(new TeamFallenEvent(victimTeam));
            } else {
                var respawnTime = Utils.find(config.unitRespawnTime, item -> item.unit == e.unit.type,
                        item -> item.respawnTime);

                if (respawnTime == null) {
                    respawnTime = Duration.ofSeconds(10);
                    Log.warn("Missing respawn time for unit @", e.unit.type.name);
                }

                var timeStr = TimeUtils.toString(respawnTime);

                victimTeam.respawn.addUnit(e.unit.type, respawnTime);

                victimTeam.eachMember(player -> {
                    player.sendMessage(
                            I18n.t(player, "[scarlet]", e.unit.type.emoji(), "@destroyed! Respawning in", timeStr));
                });
            }
        }
    }

    @Listener
    public void onUnitDestroy(UnitBulletDestroyEvent e) {
        if (coreUnits.contains(e.unit.type)) {
            return;
        }

        var bulletOwner = e.bullet.owner();

        if (bulletOwner instanceof Teamc teamc) {
            var killerTeam = teams.find(team -> team.team.id == teamc.team().id);
            if (killerTeam == null) {
                Log.warn("Missing team for unit @", e.unit.type.name);
                return;
            }

            var exp = Utils.find(config.unitExp, item -> item.unit == e.unit.type, item -> item.exp);

            if (exp == null) {
                exp = 10;
                Log.warn("Missing exp for unit @", e.unit.type.name);
            }

            killerTeam.level.addExp(exp);
            displayExp(killerTeam, exp, e.unit.x, e.unit.y);

            CataliTeamData victimTeam = teams.find(team -> team.team.id == e.unit.team.id);

            // Catch stray unit
            if (victimTeam == null) {
                spawnUnitForTeam(killerTeam, e.unit.type);
                Utils.forEachPlayerLocale((locale, players) -> {
                    String message = I18n.t(locale, "[green]", "@Team", killerTeam.team.id,
                            "@has caught a stray unit!");

                    for (var player : players) {
                        player.sendMessage(message);
                    }
                });
            }
        }
    }

    @Listener
    public void onBlockDestroy(BuildingBulletDestroyEvent e) {
        var bulletOwner = e.bullet.owner();

        if (bulletOwner instanceof Teamc teamc) {
            var killerTeam = teams.find(team -> team.team.id == teamc.team().id);
            if (killerTeam == null) {
                Log.warn("Missing team for block @", e.build.block.name);
                return;
            }

            var exp = Utils.find(config.blockExp, item -> item.block == e.build.block, item -> item.exp);

            if (exp == null) {
                exp = 10;
                Log.warn("Missing exp for block @", e.build.block.name);
            }

            killerTeam.level.addExp(exp);
            displayExp(killerTeam, exp, e.build.x, e.build.y);
        }
    }

    private void displayExp(CataliTeamData team, int amount, float x, float y) {
        team.eachMember(player -> {
            Call.label(player.con, "[green]+" + amount + "exp", 2, x + Mathf.random(5), y + Mathf.random(5));
        });
    }

    private Unit spawnUnitForTeam(CataliTeamData data, UnitType type) {
        var leaderPlayer = Groups.player.find(p -> p.uuid().equals(data.leaderUuid));

        if (leaderPlayer == null) {
            Log.info("No leader player for team @", data.team);
            return null;
        }

        Unit leaderUnit = Groups.unit.find(u -> u == leaderPlayer.unit());

        if (leaderUnit == null) {
            Log.info("No leader unit for player @", leaderPlayer.name);
            return null;
        }

        Tile safeTile = null;
        Tile tile = null;
        int maxSearchRange = 10;
        // Search for safe tile arround

        for (int i = 1; i < maxSearchRange; i++) {
            for (int x = 1 - i; x < i; x++) {
                tile = Vars.world.tile(leaderUnit.tileX() + x, leaderUnit.tileY() - i);
                if (isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
                tile = Vars.world.tile(leaderUnit.tileX() + x, leaderUnit.tileY() + i);
                if (isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
            }

            for (int y = 1 - i; y < i; y++) {
                tile = Vars.world.tile(leaderUnit.tileX() - i, leaderUnit.tileY() + y);
                if (isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
                tile = Vars.world.tile(leaderUnit.tileX() + i, leaderUnit.tileY() + y);
                if (isTileSafe(tile, type)) {
                    safeTile = tile;
                    break;
                }
            }
        }

        if (safeTile == null) {
            Log.info("No safe tile found for team @", data.team);
            return null;
        }

        Unit u = type.create(data.team);
        u.set(safeTile.worldx(), safeTile.worldy());
        u.add();

        // Apply upgrades
        u.maxHealth *= data.upgrades.healthMultiplier;
        u.health = u.maxHealth;
        u.damageMultiplier(data.upgrades.damageMultiplier);

        return u;
    }

    public boolean hasTeam(int id) {
        return teams.contains(team -> team.team.id == id);
    }

    public void assignUnitForPlayer(CataliTeamData team, Player player) {
        var freeUnit = Groups.unit
                .find(unit -> unit.team == team.team && Groups.player.find(p -> p.unit() == unit) == null);

        if (freeUnit != null) {
            player.unit(freeUnit);
            Log.info("Assigned unit @ to player @", freeUnit.type, player.name);
        } else {
            Log.info("No available unit for player @", player.name);
        }
    }

    public CataliTeamData createTeam(Player leader) {
        var playerTeam = findTeam(leader);

        if (playerTeam == null) {
            int id = 10;
            while (hasTeam(id) || Vars.state.rules.waveTeam.id == id || id == SPECTATOR_TEAM.id) {
                id++;
                if (id > 250) {
                    throw new RuntimeException("Failed to find a free team ID");
                }
            }

            Team newTeam = Team.get(id);
            playerTeam = new CataliTeamData(newTeam, leader.uuid());

            teams.add(playerTeam);
        } else {
            leader.sendMessage(I18n.t(leader, "@You already have a team!"));
        }

        leader.team(playerTeam.team);

        if (!playerTeam.hasUnit()) {
            playerTeam.spawning = true;
            leader.sendMessage(I18n.t(leader, "@Tap to spawn"));
        }

        return playerTeam;
    }

    public boolean isTileSafe(Tile tile, UnitType type) {
        return tile != null && tile.block() == Blocks.air
                && !Groups.unit.intersect(tile.worldx(), tile.worldy(), type.hitSize, type.hitSize).any();
    }

    @Data
    public static class TeamFallenEvent {
        public final CataliTeamData team;

        public TeamFallenEvent(CataliTeamData team) {
            this.team = team;
        }
    }
}
