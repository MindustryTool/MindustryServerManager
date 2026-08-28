# tower-defense-turret-exp

## Purpose

Track turret ownership and reward player experience for turret kills in Tower Defense gamemode while preserving ownership across rebuilds.

## Requirements

### Requirement: Tower Defense Turret Ownership Persistence
The plugin SHALL track player ownership of turrets placed in Tower Defense gamemodes (`TowerDefense` or `td`). When a turret is placed, the tile position SHALL be registered to the original builder. If the turret is partially or fully deconstructed by another player and rebuilt on that same tile, the system SHALL retain the original builder as the owner for EXP rewards.

#### Scenario: Original builder registered on turret placement
- **WHEN** a player places and completes a turret on an unowned tile in Tower Defense mode
- **THEN** the system registers the tile position with the builder's UUID as the original owner

#### Scenario: Ownership persists across deconstruction and rebuild by another player
- **WHEN** player B deconstructs or rebuilds a turret on a tile position previously owned by player A
- **THEN** the system retains player A's UUID as the turret tile owner, preventing player B from stealing ownership

#### Scenario: Enemy destruction clears ownership
- **WHEN** a turret is destroyed by enemy units in Tower Defense mode
- **THEN** the system clears the tile position from the ownership registry, allowing new turrets placed on that tile to establish fresh ownership

### Requirement: Deconstruction Permission
In Tower Defense mode, the system SHALL allow players to deconstruct turrets built by other players without blocking the deconstruction action.

#### Scenario: Player deconstructs another player's turret
- **WHEN** player B attempts to deconstruct a turret originally placed by player A in Tower Defense mode
- **THEN** the action filter allows the deconstruction to proceed

### Requirement: Player EXP Gain from Turret Kills
When a unit is destroyed by a turret in Tower Defense mode, the system SHALL calculate EXP as `unit.maxHealth / 100f` and grant this EXP to the registered original owner of that turret tile via `ExpGainEvent`.

#### Scenario: Turret kills unit and awards EXP to original owner
- **WHEN** a turret at tile position `P` destroys an enemy unit with max health `H` in Tower Defense mode
- **THEN** the system awards `H / 100f` EXP via `ExpGainEvent` to the original owner registered for tile `P`, even if another player recently rebuilt or repaired the turret

#### Scenario: Unowned turret kill awards no EXP
- **WHEN** a unit is killed by a turret tile that has no registered owner
- **THEN** no player EXP event is fired for that kill
