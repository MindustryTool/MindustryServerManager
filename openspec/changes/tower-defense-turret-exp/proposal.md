## Why

In Tower Defense gamemode, players need incentives for constructing turrets and defenses. While players should still be allowed to deconstruct existing turrets (e.g. to clear space or reconfigure defenses), anti-griefing protections are required so players cannot partially deconstruct or break another player's turret and rebuild it to steal the original owner's kill EXP.

## What Changes

- **Tower Defense Gamemode Gating**: Restrict turret EXP gain and ownership tracking exclusively to when Tower Defense mode is active (`Gamemode.active("TowerDefense")` or `GamemodeCondition` for "TowerDefense").
- **Turret Ownership Tracking & Persistence**: Track original player ownership when turrets are constructed. If a player deconstructs (partially or fully) another player's turret and rebuilds on that tile position, ownership remains with the original builder (`Player A`), preventing `Player B` from stealing EXP credit.
- **Deconstruction Allowed**: Players are allowed to deconstruct turrets built by other players. Action filters do NOT block deconstructing/breaking turrets.
- **Turret Destroy Unit EXP Gain**: When a turret destroys a unit in Tower Defense mode, calculate `unit.maxHealth / 100f` EXP and reward it to the original player owner via `ExpGainEvent`.
- **Enemy Destruction Reset**: Only when a turret is destroyed by enemy units is the tile ownership registry cleared, allowing new turrets placed on that tile to establish new ownership.

## Capabilities

### New Capabilities
- `tower-defense-turret-exp`: Player EXP rewards for turret kills and turret ownership persistence against rebuild EXP theft in Tower Defense gamemode.

### Modified Capabilities

## Impact

- Affected code: New/updated services under `plugin.gamemode.towerdefense` or `plugin.gamemode.survival` / core plugin event listeners.
- Uses `plugin.session.SessionService`, `ExpGainEvent`, and Mindustry events (`BlockBuildEndEvent`, `BlockDestroyEvent`, `UnitDestroyEvent`).
