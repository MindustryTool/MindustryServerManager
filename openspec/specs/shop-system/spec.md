# shop-system Specification

## Purpose
TBD - created by archiving change add-money-system. Update Purpose after archive.
## Requirements
### Requirement: Gacha rolling system

The plugin SHALL provide a gacha lucky roll system allowing players to spend coins for randomized rewards.

#### Scenario: Single gacha roll with sufficient funds
- **WHEN** a player performs a gacha roll and has sufficient coins
- **THEN** the roll cost is deducted from their wallet
- **AND** a random reward from the loot table (e.g., coins, exclusive trails, temporary buffs) is awarded to the player
- **AND** the result is announced to the player

#### Scenario: Gacha roll with insufficient funds
- **WHEN** a player attempts a gacha roll without enough coins
- **THEN** no coins are deducted and an error message is displayed

#### Scenario: Duplicate trail reward from gacha
- **WHEN** a gacha roll awards a trail that the player already owns
- **THEN** the duplicate trail is converted into a compensatory coin refund

### Requirement: Pet support unit summons

The plugin SHALL allow players to spend coins to spawn a pet support unit (e.g., Poly, Mega) that assists them in building, mining, and repairing.

#### Scenario: Successful pet summon
- **WHEN** a player purchases a pet unit while off cooldown and below the per-wave limit
- **THEN** the coin cost is deducted, the support unit spawns near the player, and the summon cooldown starts

#### Scenario: Pet summon blocked by cooldown
- **WHEN** a player attempts to spawn a pet while their summon cooldown is still active
- **THEN** the summon is rejected with a remaining cooldown notification and no coins are charged

#### Scenario: Pet summon blocked by wave limit
- **WHEN** a player attempts to spawn a pet after reaching their maximum allowed summons for the current wave
- **THEN** the summon is rejected with a wave limit reached message and no coins are charged

### Requirement: Core unit upgrades via content patch

The plugin SHALL allow players to spend coins to upgrade their core unit capabilities (such as health, build speed, mine speed, weapons, or unit tier) via server content patching.

#### Scenario: Purchasing core unit upgrade
- **WHEN** a player purchases a core unit tier upgrade with sufficient coins
- **THEN** the upgrade cost is deducted from their wallet
- **AND** the player's core unit is upgraded with patched attributes and enhanced stats
- **AND** subsequent respawns apply the upgraded core unit profile

#### Scenario: Insufficient funds for core unit upgrade
- **WHEN** a player attempts a core unit upgrade with insufficient coins
- **THEN** no coins are deducted and an insufficient funds message is shown

### Requirement: Shop and Gacha user interfaces

The plugin SHALL provide both interactive GUI dialogs (`/shop`, `/gacha`) and chat commands (`/buy <item>`, `/gacha [roll]`) for all purchasable features. All menu titles, item descriptions, and status messages SHALL be localized using `Tr.t` and SHALL use native `mindustry.gen.Iconc` characters rather than unsupported Unicode emojis.

#### Scenario: Opening shop menu
- **WHEN** a player executes `/shop`
- **THEN** a menu is displayed offering options dynamically loaded from `ShopRegistry` with localized labels and font-safe icons

#### Scenario: Opening gacha menu
- **WHEN** a player executes `/gacha`
- **THEN** a menu is displayed showing roll costs, current player balance, and roll buttons rendered with localized strings and font-safe glyphs

### Requirement: Extensible shop item strategy registry

The plugin SHALL provide a `ShopRegistry` component that registers `ShopItem` implementations. Each `ShopItem` SHALL encapsulate an identifier, localized title and description, dynamic price, availability check, and purchase execution logic.

#### Scenario: Shop items queried through registry
- **WHEN** the shop menu or `/buy` command looks up available items
- **THEN** `ShopRegistry.getItems()` returns all registered `ShopItem` strategies
- **AND** items are evaluated for player availability via `item.isAvailable(session)`

#### Scenario: Dynamic purchase execution
- **WHEN** a player purchases an item via command or menu
- **THEN** `ShopRegistry.get(itemId)` resolves the strategy
- **AND** the strategy validates funds, executes the transaction, and returns a result message

### Requirement: Reusable weighted loot table for gacha

The plugin SHALL use a generic `LootTable<T>` weighted pool to determine gacha rewards.

#### Scenario: Rolling with weighted chances
- **WHEN** a gacha roll is executed
- **THEN** `LootTable.roll(random)` selects a reward according to the configured item weights

### Requirement: Purchase action rate-limiting and thread safety

The plugin SHALL enforce a minimum interval of 400ms between shop purchases and gacha rolls per player to prevent concurrent macro spam. All state and coin deductions during purchases SHALL be synchronized per session.

#### Scenario: Rapid purchase clicks throttled
- **WHEN** a player executes multiple purchase or gacha roll commands within less than 400ms
- **THEN** requests arriving before the cooldown expires are rejected without deducting coins or firing purchases

#### Scenario: Concurrent purchase race condition prevented
- **WHEN** multiple purchase commands arrive simultaneously for the same player
- **THEN** execution is synchronized so that wallet balances and tier levels remain strictly atomic and consistent

