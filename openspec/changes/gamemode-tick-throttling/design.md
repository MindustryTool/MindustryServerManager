## Context

Mindustry loops at 60 ticks per second. Running heavy loops every tick for world buildings or unit/block spawning wastes CPU cycles and drops server TPS.

## Goals / Non-Goals

**Goals:**
- Reduce main thread CPU time spent on gamemode loops.
- Prevent network spam from high-frequency block spawning in Catali.
- Ensure thread safety for unit health upgrades.

**Non-Goals:**
- Changing gamemode rules, scoring, or gameplay balance.
- Optimizing database access (handled in `async-database-and-io`).

## Decisions

### 1. Ziger 500ms Periodic Refill
- Change `ZigerGamemode.tick()` to `@Schedule(fixedDelay = 500, unit = TimeUnit.MILLISECONDS)`.
- *Rationale*: Buildings in Mindustry have internal item buffers; replenishing every 500ms is imperceptible to gameplay while eliminating ~58 per-tick scans every second.

### 2. Catali 1s Spawning and Healing
- Move `blockSpawner.spawn(BLOCK_TEAM)` and boss spawn checks from `@Trigger(Trigger.update)` to `@Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)`.
- *Rationale*: Spawning does not need sub-second granularity. 1-second cadence prevents tile update packet bursts when many blocks are missing.

### 3. Thread-Safe Unit Health Maintenance
- Add `@MainThread` to `CoreUnitUpgradeService.maintainUpgrades()`.
- *Rationale*: Modifying `unit.maxHealth` and `unit.health` directly on a background executor causes thread safety violations.

## Risks / Trade-offs

- **[Rapid building consumption in Ziger]** → 500ms delay is within standard conveyor transit time and building input capacity.
