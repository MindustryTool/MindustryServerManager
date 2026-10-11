## ADDED Requirements

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

The plugin SHALL provide both interactive GUI dialogs (`/shop`, `/gacha`) and chat commands (`/buy <item>`, `/gacha [roll]`) for all purchasable features.

#### Scenario: Opening shop menu
- **WHEN** a player executes `/shop`
- **THEN** a menu is displayed offering options for Pet Units, Core Upgrades, and Gacha Rolls

#### Scenario: Opening gacha menu
- **WHEN** a player executes `/gacha`
- **THEN** a menu is displayed showing roll costs, current player balance, and roll buttons
