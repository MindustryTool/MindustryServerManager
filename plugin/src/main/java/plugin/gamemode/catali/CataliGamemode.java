package plugin.gamemode.catali;

import arc.Core;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Strings;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.ai.types.DefenderAI;
import mindustry.content.UnitTypes;
import mindustry.game.EventType.*;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.type.unit.MissileUnitType;
import plugin.Control;
import plugin.PluginEvents;
import plugin.annotations.Gamemode;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Persistence;
import plugin.event.SessionCreatedEvent;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliBuffRareUpgrade;
import plugin.gamemode.catali.event.CataliSpawnRareUpgrade;
import plugin.gamemode.catali.event.CataliTierRareUpgrade;
import plugin.gamemode.catali.event.ExpGainEvent;
import plugin.gamemode.catali.event.TeamCreatedEvent;
import plugin.gamemode.catali.event.TeamFallenEvent;
import plugin.gamemode.catali.event.TeamUnitDeadEvent;
import plugin.gamemode.catali.event.TeamUpgradeChangedEvent;
import plugin.gamemode.catali.event.TrayUnitCaughtEvent;
import plugin.gamemode.catali.menu.CommonUpgradeMenu;
import plugin.gamemode.catali.menu.RareUpgradeMenu;
import plugin.gamemode.catali.spawner.BlockSpawner;
import plugin.gamemode.catali.spawner.SpawnerHelper;
import plugin.gamemode.catali.spawner.UnitSpawner;
import plugin.service.I18n;
import plugin.service.SessionHandler;
import plugin.utils.TimeUtils;
import plugin.utils.Utils;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Gamemode("catali")
@RequiredArgsConstructor
public class CataliGamemode {

    @Persistence("catali-teams.json")
    private final Seq<CataliTeamData> teams = new Seq<>();

    public Seq<CataliTeamData> getAllTeams() {
        return teams;
    }

    private final UnitSpawner unitSpawner;
    private final BlockSpawner blockSpawner;
    private final CataliConfig config;
    private final SessionHandler sessionHandler;

    private final Seq<UnitType> coreUnits = Seq.with(UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma, UnitTypes.evoke,
            UnitTypes.incite, UnitTypes.emanate);

    private final Team SPECTATOR_TEAM = Team.get(255);
    private final Team ENEMY_TEAM = Team.crux;
    private final Team BLOCK_TEAM = Team.get(254);

    @Init
    public void init() {
        UnitTypes.collaris.speed /= 2;
        UnitTypes.omura.weapons.get(0).bullet.damage /= 2;

        Vars.content.units().forEach(unit -> {
            unit.flying = unit.naval ? true : unit.flying;
            unit.aiController = () -> new DefenderAI();
        });

        Vars.netServer.assigner = (player, players) -> {
            return SPECTATOR_TEAM;
        };

        applyGameRules();

        Control.SCHEDULER.scheduleWithFixedDelay(this::updateStatsHud, 0, 1, TimeUnit.SECONDS);
        Control.SCHEDULER.scheduleWithFixedDelay(this::update, 0, 1, TimeUnit.SECONDS);
        Control.SCHEDULER.scheduleWithFixedDelay(this::spawn, 0, 2, TimeUnit.SECONDS);

        Log.info("[accent]Cataio gamemode loaded");
    }

    @Listener
    public void onPlay(PlayEvent event) {
        applyGameRules();
    }

    private void applyGameRules() {
        Vars.state.rules.waveTeam = ENEMY_TEAM;
        Vars.state.rules.waves = false;
        Vars.state.rules.disableUnitCap = true;

        for (var block : Vars.content.blocks()) {
            Vars.state.rules.bannedBlocks.add(block);
        }

        Vars.state.rules.canGameOver = false;
    }

    public CataliTeamData findTeam(Player player) {
        return teams.find(team -> team.members.contains(player.uuid()));
    }

    public void spawn() {
        unitSpawner.spawn(ENEMY_TEAM);
        blockSpawner.spawn(BLOCK_TEAM);
    }

    public void update() {
        if (!Vars.state.isGame()) {
            return;
        }

        Core.app.post(() -> {
            try {
                updateRespawn();
                updateTeam();
                updatePlayer();
            } catch (Exception e) {
                Log.err("Failed to update stats hud", e);
            }
        });
    }

    private void updateStatsHud() {
        for (var player : Groups.player) {
            var team = findTeam(player);

            String message = I18n.t(player, "@No team");

            if (team != null) {
                String units = team.team.data().units.size > 0
                        ? Strings.join(", ", team.team.data().units.map(u -> u.type.emoji()))
                        : "@No unit";

                String respawn = team.respawn.getRespawn().size > 0
                        ? Strings.join(" ",
                                team.respawn.getRespawn()
                                        .map(resp -> resp.type.emoji()
                                                + TimeUtils.toString(Duration.between(Instant.now(), resp.respawnAt))
                                                + " "))
                        : "@No unit";

                message = I18n.t(player, "@Team ID:", String.valueOf(team.team.id), "\n",
                        "@Level:",
                        String.valueOf(team.level.level) + " [gray](" + String.valueOf((int) team.level.currentExp)
                                + "/"
                                + String.valueOf((int) team.level.requiredExp) + ")[white]",
                        "\n",
                        "@Member:", String.valueOf(team.members.size), "\n",
                        "[sky]Hp:", String.format("%.2f", team.upgrades.getHealthMultiplier() * 100) + "%[white]\n",
                        "[red]Dmg:", String.format("%.2f", team.upgrades.getDamageMultiplier()
                                * 100) + "%[white]\n",
                        "[accent]Exp:", String.format("%.2f", team.upgrades.getExpMultiplier()
                                * 100) + "%[white]\n",
                        "[green]Regen:", String.format("%.2f", team.upgrades.getRegenMultiplier()
                                * 100) + "%[white]\n",
                        "@Upgrades:", "", String.valueOf(team.level.commonUpgradePoints), "[accent]",
                        String.valueOf(team.level.rareUpgradePoints), "[white]\n",
                        "@Unit:", units, "\n",
                        "@Respawn:", respawn + "\n");

                Call.infoPopup(player.con, message, 1.1f, Align.right | Align.top, 180, 0, 0, 0);
            }
        }
    }

    private void updatePlayer() {
        for (var player : Groups.player) {
            var team = findTeam(player);

            if (team == null) {
                Call.infoPopup(player.con, I18n.t(player, "@User", "[accent]/play[white]", "@to start a new team"), 2,
                        Align.center, 0, 0, 30, 0);
            } else if (team.level.level == 1 && team.level.currentExp == 0) {
                Call.infoPopup(player.con, I18n.t(player, "@Destroy block to get [accent]exp[white]"), 2,
                        Align.center, 0, 0, 30, 0);
            }

            if (player.unit() != null && coreUnits.contains(player.unit().type)) {
                player.team(SPECTATOR_TEAM);
                player.unit().kill();
            }
        }
    }

    private void updateRespawn() {
        for (CataliTeamData data : teams) {
            var respawns = data.respawn.getRespawnUnit();
            for (var entry : respawns) {
                data.spawnUnit(entry.type, (spawned) -> {
                    data.eachMember(member -> {
                        member.sendMessage(I18n.t(member, spawned.type.emoji(), "@respaned"));
                    });
                });
            }

            data.attempToSpawn();
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

        var leader = event.team.getLeader();
        var teamName = leader != null ? leader.name : event.team.team.id;

        Groups.unit.forEach(unit -> {
            if (unit.team == event.team.team) {
                unit.kill();
            }
        });

        Utils.forEachPlayerLocale((locale, players) -> {
            String message = I18n.t(locale, "[scarlet]", "@Team", teamName, "@has been eliminated!");
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
    public void onTeamUpgradeChanged(TeamUpgradeChangedEvent event) {
        var team = event.team;

        Groups.unit.forEach(unit -> {
            if (unit.team == team.team) {
                team.upgrades.apply(unit);
            }
        });
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
            createTeam(player);
        }
    }

    @Listener
    public void onUnitBuff(CataliBuffRareUpgrade event) {
        var team = event.team;
        var unit = event.unit;
        var buff = event.effect;

        unit.apply(buff);

        team.level.rareUpgradePoints--;
    }

    @Listener
    public void onUnitUpgrade(CataliTierRareUpgrade event) {
        var team = event.team;
        var unit = event.unit;
        var upgrade = event.upgradeTo;

        team.spawnUnit(upgrade, spawned -> {
            unit.kill();
        });

        team.level.rareUpgradePoints--;
    }

    @Listener
    public void onTeamCreated(TeamCreatedEvent event) {

    }

    @Listener
    public void onTap(TapEvent event) {
        var playerTeam = findTeam(event.player);

        if (playerTeam != null && playerTeam.spawning == true) {
            var spawnX = event.tile.worldx();
            var spawnY = event.tile.worldy();

            var spawnable = SpawnerHelper.isTileSafe(event.tile, UnitTypes.poly);

            if (spawnable) {
                Unit unit = UnitTypes.poly.create(playerTeam.team);

                unit.set(spawnX, spawnY);
                Core.app.post(() -> {
                    unit.add();
                    event.player.unit(unit);
                });

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

        if (e.unit.type instanceof MissileUnitType) {
            return;
        }

        CataliTeamData victimTeam = teams.find(team -> team.team.id == e.unit.team.id);

        if (victimTeam == null) {
            return;
        }

        if (victimTeam.hasUnit()) {
            PluginEvents.fire(new TeamUnitDeadEvent(victimTeam, e.unit.type));
        } else {
            PluginEvents.fire(new TeamFallenEvent(victimTeam));
        }
    }

    @Listener
    public void onTeamUnitDead(TeamUnitDeadEvent event) {
        var respawnTime = Utils.find(config.unitRespawnTime, item -> item.unit == event.type, item -> item.respawnTime);

        if (respawnTime == null) {
            respawnTime = Duration.ofSeconds(10);
            Log.warn("Missing respawn time for unit @", event.type.name);
        }

        var timeStr = TimeUtils.toRelativeSeconds(respawnTime);

        event.team.respawn.addUnit(event.type, respawnTime);

        event.team.eachMember(player -> {
            player.sendMessage(
                    I18n.t(player, event.type.emoji(), "[scarlet]", "@destroyed! Respawning in", "[accent]", timeStr));
        });
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
                return;
            }

            var exp = Utils.find(config.unitExp, item -> item.unit == e.unit.type, item -> item.exp);

            if (exp == null) {
                exp = 10;
                Log.warn("Missing exp for unit @", e.unit.type.name);
            }

            PluginEvents.fire(new ExpGainEvent(killerTeam, exp, e.unit.x, e.unit.y));

            CataliTeamData victimTeam = teams.find(team -> team.team.id == e.unit.team.id);

            if (victimTeam == null) {
                // Cal chances
                PluginEvents.fire(new TrayUnitCaughtEvent(killerTeam, e.unit.type));
            }
        }
    }

    @Listener
    public void onTrayUnitCaught(TrayUnitCaughtEvent event) {
        Utils.forEachPlayerLocale((locale, players) -> {
            String message = I18n.t(locale, "[green]", "@Team", event.team.team.id,
                    "@has caught a stray unit!");

            for (var player : players) {
                player.sendMessage(message);
            }
        });

        event.team.spawnUnit(event.type, unit -> {

        });
    }

    @Listener
    public void onRareUpgradeSpawn(CataliSpawnRareUpgrade event) {
        event.team.spawnUnit(UnitTypes.poly, unit -> {
        });
        event.team.level.rareUpgradePoints--;
    }

    @Listener
    public void onBlockDestroy(BuildingBulletDestroyEvent e) {
        var bulletOwner = e.bullet.owner();

        if (bulletOwner instanceof Teamc teamc) {
            var killerTeam = teams.find(team -> team.team.id == teamc.team().id);
            if (killerTeam == null) {
                return;
            }

            var exp = Utils.find(config.blockExp, item -> item.block == e.build.block, item -> item.exp);

            if (exp == null) {
                exp = 10;
                Log.warn("Missing exp for block @", e.build.block.name);
            }

            PluginEvents.fire(new ExpGainEvent(killerTeam, exp, e.build.x, e.build.y));
        }
    }

    @Listener
    public void onExpGain(ExpGainEvent event) {
        float calAmount = event.amount * event.team.upgrades.getExpMultiplier();

        event.team.eachMember(player -> {
            Call.label(player.con, "[green]+" + calAmount + "exp", 2, //
                    event.x + Mathf.random(5),
                    event.y + Mathf.random(5));
        });

        boolean levelUp = event.team.level.addExp(calAmount);

        if (!levelUp) {
            return;
        }

        var leaderPlayer = event.team.getLeader();

        if (leaderPlayer == null) {
            return;
        }

        var session = sessionHandler.get(leaderPlayer).orElse(null);

        if (session == null) {
            Log.info("No session for leader player @", leaderPlayer.name);
            return;
        }

        if (event.team.level.commonUpgradePoints > 0) {
            new CommonUpgradeMenu().send(session, event.team);
        }

        if (event.team.level.rareUpgradePoints > 0) {
            new RareUpgradeMenu().send(session, event.team);
        }
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
            int id = 20;
            while (hasTeam(id) || ENEMY_TEAM.id == id || id == SPECTATOR_TEAM.id || id == BLOCK_TEAM.id) {
                id++;
                if (id > 240) {
                    throw new RuntimeException("Failed to find a free team ID");
                }
            }

            Team newTeam = Team.get(id);
            newTeam.rules().rtsAi = true;

            playerTeam = new CataliTeamData(newTeam, leader.uuid());

            PluginEvents.fire(new TeamCreatedEvent(playerTeam));

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
}
