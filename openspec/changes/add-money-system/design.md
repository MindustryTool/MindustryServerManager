## Context

The server currently tracks player experience (`exp`) and levels inside `SessionData`. Players need engaging sinks for earned money and structured daily goals to encourage daily play across different server gamemodes. Based on requirements, money has four direct sinks (Gacha, Trail shop, Pet spawning, Core unit upgrades) and an expanded income loop featuring gamemode-dependent daily tasks.

## Goals / Non-Goals

**Goals:**
- Provide a persistent `money` balance in `SessionData` persisted in SQLite.
- Daily task system tailored to the active server gamemode (TowerDefense, Flood, Catali, default), rewarding coins upon completion.
- Gacha system with defined loot tables and roll costs.
- Trail unlock model: persist purchased trails in `SessionData.unlockedTrails` and replace level gating.
- Pet summon manager with per-player cooldowns and per-wave limits.
- Core unit upgrades via server-side content patching and player unit replacement.
- Interactive GUI dialogs (`/shop`, `/gacha`, `/trail`, `/tasks`) and chat commands.

**Non-Goals:**
- Player-to-player coin transfers (`/pay`).
- Unlimited or unthrottled unit summoning.

## Decisions

### 1. Gamemode-Dependent Daily Task Engine
* **Decision**: Implement `DailyTaskService` which discovers task definitions dynamically based on the current server gamemode.
  * *Tower Defense tasks*: Survive $X$ waves, kill $N$ boss units, build $M$ turrets.
  * *Flood tasks*: Survive $X$ minutes, build $N$ power generators, survive flood surges.
  * *Catali tasks*: Defeat $N$ enemy team units, assist in capturing $M$ sectors.
  * *Default / Survival tasks*: Mine $N$ silicon/copper, survive $X$ waves.
* **Storage**: In `SessionData`, record `dailyTaskDate` (e.g. `2026-10-12`) and `Map<String, Integer> dailyTaskProgress`. When a player joins or a day rolls over, progress resets if `dailyTaskDate` != today.
* **Rationale**: Reuses the existing `SessionData` JSON persistence without extra tables, while tailoring gameplay objectives to each server's specific rules.

### 2. Gacha Mechanism & Loot Table
* **Decision**: Implement a `GachaService` offering single rolls (e.g., 200 coins) and multi-rolls (e.g., 10x for 1,800 coins). Loot includes coins, exclusive trails, temporary combat buffs, or instant pet summons.
* **Rationale**: Provides an exciting coin sink that rewards players with randomized prizes and duplicate protection (converting duplicate trails to bonus coins).

### 3. Trail Economy (Money instead of Level)
* **Decision**: Add `public List<String> unlockedTrails = new ArrayList<>();` to `SessionData`. In `TrailMenu`, trails display their coin price if unowned. Clicking buys and permanently unlocks the trail. Owned trails display the equip button.
* **Rationale**: Replaces artificial level gates with direct player choice and progression.

### 4. Pet (Support Unit) Spawner
* **Decision**: Implement `PetService` which summons support units (e.g., Poly, Mega) assigned to follow and aid the player. Enforce a 60s cooldown and a maximum of 2 active pets per player per wave.
* **Rationale**: Provides clear in-match assistance while guarding against server entity overload.

### 5. Core Unit Upgrade via Content Patch
* **Decision**: Implement `CoreUnitUpgradeService`. Mindustry server plugins can modify `UnitType` attributes (health, buildSpeed, mineSpeed, weapons) dynamically or replace the player's core unit instance upon respawn/upgrade. Core unit upgrades persist per session or per match (tier 1: Alpha+ -> tier 2: Beta+ -> tier 3: Gamma+).
* **Rationale**: Content patching allows customizing unit capabilities without requiring players to download client-side mods.

### 6. Thread-Safe Concurrency
* **Decision**: Use `MoneyService` to atomically modify player wallets and mark sessions dirty in `SessionRepository`.

## Risks / Trade-offs

- **[Gamemode switching mid-session]** → If a server switches maps or gamemodes, evaluate task progress gracefully so players do not lose completed rewards from the same day.
- **[Lag from entity spam]** → Strict cooldowns and wave limits prevent players from flooding the map with pets.
- **[Content patch mismatch]** → When tweaking unit stats via content patch, sync properties supported by server-side RPCs (weapons, health multipliers, speed) so vanilla clients do not desync.
- **[Gacha frustration]** → Guarantee consolation rewards (coins/buffs) and duplicate trail compensation.
