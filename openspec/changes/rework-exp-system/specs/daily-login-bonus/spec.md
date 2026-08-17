## ADDED Requirements

### Requirement: Daily login tracking table

The plugin SHALL create a `player_logins` table in the SQLite database with the columns `uuid TEXT PRIMARY KEY` and `last_login_date TEXT NOT NULL`, where `last_login_date` is the server-local calendar day (`LocalDate.now().toString()`) on which the player last joined.

The plugin SHALL provide a `DailyRepository` `@Component` exposing at least `getLastLogin(String uuid)` returning the stored date (or empty if no record) and `setLastLogin(String uuid, String date)`.

The daily process SHALL be triggered by a `DailyService` `@Component` listening to `SessionCreatedEvent` (fired synchronously when a session is created), rather than being hardcoded in `SessionService`.

#### Scenario: Table exists after init
- **WHEN** the plugin initializes
- **THEN** a `player_logins` table exists and the `DailyRepository` can read and write login dates

### Requirement: Daily login bonus on new-day login

When a `SessionCreatedEvent` fires (player joined), the plugin SHALL compare their last login date with today's date:
- if a record exists and the date differs from today, the plugin SHALL update the login date to today, grant **3600 exp** to the player's stored exp counter, and send the player a message stating the daily login bonus;
- if a record exists and the date equals today, the plugin SHALL take no action.

The bonus SHALL be applied to the stored exp counter before the session's level settles, so the player's level reflects the bonus immediately.

#### Scenario: Returning player logs in on a new day
- **WHEN** a `SessionCreatedEvent` fires for a player whose last login was on a previous day
- **THEN** their `data.exp` increases by 3600, the `player_logins` date is updated to today, and the player receives a daily login bonus message

#### Scenario: Returning player logs in twice the same day
- **WHEN** a `SessionCreatedEvent` fires for a player who already joined today
- **THEN** no bonus is granted and the stored login date is unchanged

### Requirement: No bonus on first-ever login

When a `SessionCreatedEvent` fires for a player with no existing record in `player_logins`, the plugin SHALL insert a record with today's date but SHALL NOT grant the daily bonus.

#### Scenario: New player joins for the first time
- **WHEN** a player with no `player_logins` record joins
- **THEN** a `player_logins` record is created with today's date
- **AND** their stored exp counter is not increased by the daily bonus
- **AND** no daily login bonus message is sent
- **AND** on their next join on a different day, the daily bonus is granted