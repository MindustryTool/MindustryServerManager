# economy-admin Specification

## Purpose
TBD - created by archiving change economy-anti-abuse-and-admin. Update Purpose after archive.
## Requirements
### Requirement: Administrative money commands

The plugin SHALL provide an admin command `/admin-money <inspect|give|take|set>` restricted to server administrators (`admin = true`).
- `inspect <player>`: SHALL display the target player's coin balance, core unit upgrade tier, and daily task completion.
- `give <player> <amount>`: SHALL deposit `amount` coins to the target player's wallet.
- `take <player> <amount>`: SHALL deduct `amount` coins from the target player's wallet down to a minimum of 0.
- `set <player> <amount>`: SHALL set the target player's balance directly to `amount` (minimum 0).

#### Scenario: Admin inspects a player
- **WHEN** an admin executes `/admin-money inspect <player>`
- **THEN** a localized message is displayed showing the player's balance, core unit tier, and task progress

#### Scenario: Admin adjusts player balance
- **WHEN** an admin executes `/admin-money give <player> 500`
- **THEN** 500 coins are credited to the target player
- **AND** a localized confirmation message is sent to the admin

#### Scenario: Non-admin denied access
- **WHEN** a regular non-admin player executes `/admin-money`
- **THEN** the server denies access

### Requirement: Administrative daily task reset command

The plugin SHALL provide an admin command `/admin-tasks reset <player>` restricted to server administrators (`admin = true`) to clear a player's daily task progress and re-enable task completion for today.

#### Scenario: Admin resets player daily tasks
- **WHEN** an admin executes `/admin-tasks reset <player>`
- **THEN** the player's `dailyTaskProgress` is cleared
- **AND** a localized confirmation message is displayed to the admin

