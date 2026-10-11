## Why

Players currently earn experience and level up, but have limited currency sinks, gameplay goals, and daily engagement loops. Adding a persistent economy rewards combat prowess, active playtime, and daily task completion with spendable currency. This currency unlocks four concrete features: rolling a gacha system for rare rewards, purchasing trails with coins instead of player level, spawning pet support units, and upgrading core units via server content patching.

## What Changes

- Add a persistent player currency ("coins") stored in `SessionData` and serialized through SQLite.
- Introduce income sources:
  - Combat rewards: earn coins on enemy unit kills and wave completions.
  - Playtime rewards: passive coins earned periodically while actively playing (non-AFK).
  - Daily login rewards: daily coins granted on returning login via `DailyService`.
  - Daily task system: daily objectives tailored to the active server gamemode (e.g., Tower Defense wave survival, Flood milestones, Catali unit defeats) that award coins upon completion.
- Implement 4 main money use cases:
  1. **Quay gacha (Gacha roll)**: Spend coins to roll for random prizes (rare trails, coins, bonuses).
  2. **Mua /trail bằng tiền thay vì level**: Buy trails with coins directly instead of unlocking by player level; persist unlocked trails per player.
  3. **Spawn pet (support unit)**: Spend coins to summon a pet builder/repair drone (e.g., Poly, Mega) to assist the player, with cooldowns and wave limits.
  4. **Upgrade core unit qua content patch**: Spend coins to upgrade the player's core unit tiers and capabilities dynamically using content patching.
- Provide interactive GUI menus (`/shop`, `/gacha`, `/trail`, `/tasks`) and chat commands (`/buy <item>`, `/money`, `/tasks`).

## Capabilities

### New Capabilities
- `money-system`: Tracks player wallet balance, handles deposit/withdraw operations, provides persistence in `SessionData`, and awards coins for combat, playtime, and daily login.
- `shop-system`: Provides shop catalog, gacha rolling mechanism, pet support unit summons with cooldowns, and core unit upgrades via content patching.
- `daily-tasks`: Generates gamemode-specific daily tasks, tracks daily progress per player in `SessionData`, and awards coins upon task completion.

### Modified Capabilities
- `daily-login-bonus`: Update daily login reward logic to grant daily bonus coins alongside daily experience.
- `trail-selection`: Modify trail requirements so trails are purchased and unlocked using money instead of being gated by player level.

## Impact

- `plugin.session.SessionData`: Add `money`, `unlockedTrails`, and daily task tracking fields (`dailyTaskDate`, `dailyTaskProgress`).
- `plugin.gamemode`: Implement gamemode-specific task providers (TowerDefense, Flood, Catali, default).
- `plugin.trail.TrailService` / `TrailMenu`: Replace level requirement checks with owned/purchased checks and buy actions.
- `plugin.shop`: Add Gacha rolling, Pet summon dispatcher, and Core Unit upgrade handlers.
- Database: Persisted seamlessly within existing `sessions.data` JSON payload.
