## 1. Core Wallet & Session Persistence

- [ ] 1.1 Add `money`, `unlockedTrails`, `dailyTaskDate`, and `dailyTaskProgress` fields to `SessionData`
- [ ] 1.2 Implement `MoneyService` `@Component` with `deposit`, `withdraw`, and balance query methods
- [ ] 1.3 Ensure `SessionRepository` serializes wallet and daily task data cleanly in SQLite

## 2. Income Sources & Daily Tasks

- [ ] 2.1 Hook enemy unit destruction events to award tier-based combat coins to players
- [ ] 2.2 Hook wave completion events to award wave bonus coins to active players
- [ ] 2.3 Implement periodic playtime coin rewards in `MoneyService` excluding AFK players
- [ ] 2.4 Update `DailyService` to award 500 bonus coins on new-day login
- [ ] 2.5 Implement `DailyTaskService` with gamemode-specific task sets (TowerDefense, Flood, Catali, default)
- [ ] 2.6 Implement daily task progress listener, daily rollover reset, and coin reward payout

## 3. Trail Purchasing Overhaul

- [ ] 3.1 Refactor `TrailService` to support coin pricing and ownership checks against `unlockedTrails`
- [ ] 3.2 Update `TrailMenu` to show purchase button for unowned trails and toggle equip for owned trails

## 4. Pet Spawner & Core Unit Upgrades

- [ ] 4.1 Implement `PetService` for summoning support units (Poly, Mega) with cooldowns and per-wave caps
- [ ] 4.2 Implement `CoreUnitUpgradeService` applying content-patched stats and tiers to player core units

## 5. Gacha System, Menus & Commands

- [ ] 5.1 Implement `GachaService` with randomized loot table, single/multi rolls, and duplicate compensation
- [ ] 5.2 Implement `ShopMenu`, `GachaMenu`, and `TaskMenu` dialogs extending `PluginMenu`
- [ ] 5.3 Register `/shop`, `/gacha`, `/tasks`, `/buy <item>`, and `/money` (alias `/balance`) client commands

## 6. Verification & Testing

- [ ] 6.1 Unit tests for `MoneyService` balance mutations and thread-safety
- [ ] 6.2 Unit tests for `DailyTaskService` gamemode resolution, progress rollover, and reward payout
- [ ] 6.3 Unit tests for `TrailService` trail purchase and unlock validation
- [ ] 6.4 Unit tests for `PetService` cooldown enforcement and wave limits
- [ ] 6.5 Unit tests for `GachaService` roll mechanics and duplicate trail refunds
