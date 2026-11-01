# money-system Specification

## Purpose
TBD - created by archiving change add-money-system. Update Purpose after archive.
## Requirements
### Requirement: Persistent wallet in session data

The plugin SHALL maintain a persistent `money` field of type `long` in `SessionData` representing the player's wallet balance. When `money` changes, the session SHALL be marked dirty via `SessionRepository.markDirty(session)` so changes are saved to the database.

#### Scenario: New player starts with zero balance
- **WHEN** a new player joins with no existing record
- **THEN** their `data.money` is initialized to 0

#### Scenario: Existing player balance loaded
- **WHEN** a returning player joins
- **THEN** their `data.money` is loaded from their persistent session record

### Requirement: Money management service

The plugin SHALL provide a `@Component` class `MoneyService` that exposes methods to check balance, deposit money, attempt withdrawal, and set balance with arithmetic overflow protection (maximum balance `1,000,000,000,000` coins).

#### Scenario: Deposit money
- **WHEN** `deposit(session, amount)` is called with a positive amount
- **THEN** `session.getData().money` is incremented by that amount without exceeding the maximum balance cap
- **AND** the session is marked dirty in `SessionRepository`

#### Scenario: Successful withdrawal
- **WHEN** `withdraw(session, amount)` is called and the player has at least `amount` coins
- **THEN** `session.getData().money` is decremented by `amount`
- **AND** the method returns true
- **AND** the session is marked dirty in `SessionRepository`

#### Scenario: Insufficient funds withdrawal
- **WHEN** `withdraw(session, amount)` is called and the player has less than `amount` coins
- **THEN** the balance is not modified
- **AND** the method returns false

### Requirement: Combat and wave clear rewards

The plugin SHALL award money to players upon destroying enemy units and completing waves:
- Destroying an enemy unit SHALL only award coins if the unit is hostile (non-friendly) and not a player or pet. The reward amount SHALL be capped at a maximum of 50 coins per enemy.
- Completing a wave SHALL award bonus coins to all active players silently without sending repetitive chat notifications.

#### Scenario: Enemy unit destroyed awards coins
- **WHEN** a valid hostile enemy unit is destroyed by player turrets or units
- **THEN** contributing players receive coin rewards capped at 50 coins

#### Scenario: Friendly or player unit destroyed gives no reward
- **WHEN** a player, friendly, or pet unit is destroyed
- **THEN** zero coins are awarded

#### Scenario: Wave completion awards bonus silently
- **WHEN** a wave is successfully completed
- **THEN** every active player receives wave completion bonus coins silently without repetitive chat announcements

### Requirement: Playtime income with AFK exclusion

The plugin SHALL periodically grant income to active players during gameplay. Players marked as AFK (`session.isAfk() == true`) SHALL NOT receive playtime coin rewards.

#### Scenario: Active player earns playtime coins
- **WHEN** the periodic playtime tick fires and the player is not AFK
- **THEN** the player's wallet is credited with playtime coins

#### Scenario: AFK player does not earn playtime coins
- **WHEN** the periodic playtime tick fires and the player is AFK
- **THEN** no coins are added to the player's wallet

### Requirement: Daily task completion reward deposits

The plugin SHALL credit coins earned from completed daily tasks into the player's persistent wallet.

#### Scenario: Depositing daily task reward
- **WHEN** a player completes a daily task
- **THEN** the task reward is deposited via `MoneyService.deposit(session, reward)` and the session is marked dirty

### Requirement: Localized economy messages and font-safe balance indicators

All economy notifications, rewards, and balance messages emitted by `MoneyService`, `CombatRewardService`, and money commands SHALL be localized using `Tr.t` and use Mindustry font-safe glyphs and color tags.

#### Scenario: Player receives localized coin deposit notification
- **WHEN** coins are awarded to a player for playtime or combat
- **THEN** the notification message is translated according to the player's locale via `Tr.t` without unsupported Unicode emojis

#### Scenario: Balance command displays localized output
- **WHEN** a player executes `/money` or `/balance`
- **THEN** the balance response is formatted using localized template strings and font-safe glyphs

