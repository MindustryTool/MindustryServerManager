## Context

Currently in Mindustry, when a player deconstructs a turret and rebuilds it, standard building logic attributes the new building to the player who completed or placed the rebuild. In Tower Defense, players can exploit this by deconstructing another player's turret (partially or completely) and rebuilding it to hijack the turret's kill EXP rewards. Allowing deconstruction is important for team flexibility, but ownership must be preserved so EXP cannot be stolen.

## Goals / Non-Goals

**Goals:**
- Allow players to deconstruct any turret in Tower Defense mode (`Gamemode.active("TowerDefense", "td")`).
- Track and preserve original builder ownership (`ownerUuid`) on turret tile positions across partial deconstructions and rebuilds.
- Award EXP (`unit.maxHealth / 100f`) to the original builder owner when a turret destroys an enemy unit.
- Clear tile ownership only when a turret is destroyed by enemy units/waves or when explicitly cleared.

**Non-Goals:**
- Blocking players from deconstructing turrets built by teammates.
- Multi-gamemode ownership enforcement outside Tower Defense modes.

## Decisions

1. **Gamemode Restriction**:
   - Scope all turret ownership tracking and kill EXP rewards behind `Gamemode.active("TowerDefense", "td")`.

2. **Turret Ownership Registry & Rebuild Persistence**:
   - Maintain a mapping of tile position (`int pos`) to `String originalOwnerUuid`.
   - Listen to `BlockBuildEndEvent`:
     - If a turret is completed or built on tile `pos`:
     - Check if `pos` already has an `originalOwnerUuid` registered (from prior build or partial deconstruction/rebuild).
     - If an `originalOwnerUuid` exists for that tile, **retain `originalOwnerUuid`** as the owner.
     - If no owner is registered for `pos` (new placement or after enemy destruction), set `pos -> builderUuid`.

3. **Deconstruction vs Enemy Destruction Handling**:
   - When a player deconstructs or breaks a turret (player-initiated `breakBlock` or deconstruction), **do NOT remove `pos` from the ownership registry**. This ensures that if the tile is rebuilt by any player, the original owner receives the EXP credit.
   - When a turret is destroyed by enemy units (`BlockDestroyEvent` where damage source is enemy / non-player build action), clear `pos` from the ownership registry so future turrets placed there start fresh.

4. **Kill EXP Calculation & Event Dispatch**:
   - Listen to `UnitDestroyEvent`: check if killer was a `TurretBuild` located at tile position `pos`.
   - Retrieve `originalOwnerUuid` for `pos`.
   - Calculate `expGained = event.unit.maxHealth / 100f`.
   - Retrieve `Session` for `originalOwnerUuid` via `SessionService.get(originalOwnerUuid)`.
   - If session exists, fire `PluginEvents.fire(new plugin.session.ExpGainEvent(session, expGained))`.

## Risks / Trade-offs

- **[Risk]**: Tile position memory leak if maps restart or tile positions accumulate over long games.
  - **Mitigation**: Clear the ownership registry on map load / world load events (`WorldLoadEvent` or `ResetEvent`).
- **[Risk]**: A player rebuilds on a tile abandoned by an offline player.
  - **Mitigation**: Optionally check if `originalOwnerUuid` is still active or in sessions, or allow tile ownership transfer after an extended idle period if needed.
