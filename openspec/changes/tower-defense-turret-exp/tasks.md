## 1. Tower Defense Service Setup & Ownership Registry

- [ ] 1.1 Create `TowerDefenseTurretService.java` component gated by `GamemodeCondition` for Tower Defense mode (`"TowerDefense"`, `"td"`).
- [ ] 1.2 Implement tile position to original owner UUID mapping data structure.
- [ ] 1.3 Implement `BlockBuildEndEvent` listener to record original builder on new turret tiles and retain original owner on rebuilds.
- [ ] 1.4 Implement `BlockDestroyEvent` listener to clear tile ownership ONLY when destroyed by enemy units/waves.
- [ ] 1.5 Implement world reset/load listeners to clear the ownership registry between games.

## 2. Unit EXP Rewards on Turret Kills

- [ ] 2.1 Implement unit destruction / kill event listener to detect when a unit is killed by a turret building.
- [ ] 2.2 Calculate EXP gain as `unit.maxHealth / 100f` for turret kills in Tower Defense mode.
- [ ] 2.3 Look up registered original owner session for the turret tile position via `SessionService` and fire `ExpGainEvent`.

## 3. Anti-EXP Stealing Ownership Protection

- [ ] 3.1 Verify player action filters allow deconstruction of turrets by any player.
- [ ] 3.2 Ensure rebuilding or finishing partial construction on an existing turret tile preserves the original owner's UUID in the registry.

## 4. Verification & Testing

- [ ] 4.1 Verify code compilation using `./gradlew build` / `gradlew check`.
- [ ] 4.2 Validate that deconstruction is permitted, rebuilds do not transfer EXP ownership, and turret kills correctly award `maxHealth / 100f` EXP to the original builder.
