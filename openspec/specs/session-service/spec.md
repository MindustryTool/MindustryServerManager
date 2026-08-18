# session-service

## Purpose
TBD - created by archiving change merge-session-handler-service. Update Purpose after archive.

## Requirements
### Requirement: Unified session service component

The plugin SHALL provide a single `@Component` class `plugin.session.SessionService` that owns both the in-memory session container and session business logic, replacing the former `SessionHandler` and `SessionService` split.

`SessionService` SHALL be registered by `Registry` with the same component semantics: constructor injection of `SessionRepository`, and the `@Listener`, `@Schedule`, and `@Destroy` annotations applied to the merged class.

#### Scenario: SessionHandler no longer exists
- **WHEN** the plugin source tree is inspected
- **THEN** there is no `SessionHandler` class or type reference anywhere in `plugin/src/main/java/plugin/**`
- **AND** `SessionService` exposes the union of the former handler and service method sets

#### Scenario: Component still registered
- **WHEN** `Registry.init("plugin")` runs
- **THEN** exactly one session component (`SessionService`) is registered
- **AND** `Registry.get(SessionService.class)` returns the singleton instance

### Requirement: Session container management

`SessionService` SHALL manage the in-memory session container keyed by player uuid with the same behavior the former `SessionHandler` had.

The container SHALL expose: `get()` returning the map, `get(Player)`, `getByUuid(String)`, `put(Player)`, `remove(Player)`, `contains(Player)`, `size()`, `each(Cons<Session>)`, `each(Boolf<Session>, Cons<Session>)`, `count(Boolf<Session>)`, and `find(Boolf<Session>)`.

#### Scenario: Player join creates a session
- **WHEN** a `PlayerJoin` event fires
- **THEN** a `Session` is created for the player, backed by `SessionRepository` data, stored in the container, a `SessionCreatedEvent` fires, and `update(session)` runs

#### Scenario: Player leave removes a session
- **WHEN** a `PlayerLeave` event fires
- **THEN** the player's session is removed from the container
- **AND** a `SessionRemovedEvent` fires if a session existed

#### Scenario: Container query helpers
- **WHEN** code calls `getByUuid`, `get(Player)`, `contains`, `size`, `each`, `find`, or `count` on `SessionService`
- **THEN** results match the container contents exactly as they did under the former `SessionHandler`

### Requirement: Scheduled and teardown behavior

`SessionService` SHALL run a per-second scheduled `update(session)` over all sessions via `@Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)`.

On `@Destroy`, `SessionService` SHALL persist all sessions through `SessionRepository`, reset each session, and clear the container, exactly as the former `SessionHandler.destroy()` did.

#### Scenario: Scheduled level updates
- **WHEN** the scheduled task runs each second
- **THEN** every active session is passed to `SessionService.update`
- **AND** level-up messages and name updates fire only when a level change is detected

#### Scenario: Teardown persists sessions
- **WHEN** `Registry.destroy()` invokes `@Destroy`
- **THEN** each session is removed from `SessionRepository`, reset, and the container is cleared

### Requirement: Session business logic preserved

`SessionService` SHALL retain the business logic from the former `SessionService`: `update(Session)`, `setLogin(Session, LoginDto)`, and the `getLevel` function computing level from total exp via `ExpUtils`.

`getLevel` SHALL compute the player's level exclusively from the stored exp counter: `ExpUtils.levelFromTotalExp(data.exp)`, where `data` is the session's `SessionData`.

`update(Session)` SHALL be split into two responsibilities: a per-second `@Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)` task SHALL fire `ExpGainEvent(session, 1)` for every active session, and a single `onExpGain(ExpGainEvent)` listener SHALL apply the event amount to `session.getData().exp`, mark the session dirty via `sessionRepository.markDirty(session)`, and recompute the player's level; level-up broadcasts and name updates fire only when a level change is detected.

`setLogin` SHALL handle admin promotion/demotion through `Vars.netServer.admins`, update `session.login`, reset the player's admin flag, refresh the player name, and send the `/admin` hint when applicable.

#### Scenario: Login applied to session
- **WHEN** `setLogin(session, login)` is called with an admin login
- **THEN** the target player is administered, `session.login` is set, the player's name is refreshed, and the `/admin` toggle hint is sent

#### Scenario: Level recomputed on update
- **WHEN** `update(session)` runs and the computed level differs from `session.currentLevel`
- **THEN** `session.currentLevel` and the player name are updated
- **AND** a level-up message is broadcast to all players on level increase

#### Scenario: Exp accrued on each per-second tick
- **WHEN** the per-second scheduled task fires `ExpGainEvent(session, 1)` for a session
- **THEN** the `onExpGain` listener increments `session.getData().exp` by 1, marks the session dirty, and recomputes the player's level

#### Scenario: Level derives from stored counter
- **WHEN** `getLevel.apply(session)` runs
- **THEN** the returned level is computed from the stored `data.exp` counter via `ExpUtils`, independent of play time

### Requirement: Join-time daily login bonus handling

The daily login process SHALL NOT be hardcoded in `SessionService`. It SHALL be handled by a `DailyService` `@Component` listening to `SessionCreatedEvent`: on session creation, the player's last login date is checked, and on a new-day login the plugin SHALL fire `ExpGainEvent(session, 3600)` and notify the player, while a first-ever login grants no bonus. `SessionService.put(Player)` SHALL only create the session and fire `SessionCreatedEvent`.

#### Scenario: Session creation runs daily bonus check
- **WHEN** a `PlayerJoin` event creates a session and `SessionCreatedEvent` fires for a returning player on a new day
- **THEN** the `DailyService` listener fires `ExpGainEvent(session, 3600)` (granting the exp through the centralized listener), the login date is updated, and the daily bonus message is sent

#### Scenario: Session creation skips first-ever bonus
- **WHEN** a `PlayerJoin` event creates a session and `SessionCreatedEvent` fires for a player with no prior login record
- **THEN** a login record is created and no exp bonus or message is granted

### Requirement: Active player count helper

`SessionService` SHALL provide a `countActive()` method that returns the number of sessions where `session.isAfk()` is false.

#### Scenario: Counts non-AFK sessions
- **WHEN** `SessionService.countActive()` is called
- **THEN** it returns the count of all sessions whose `afkState` is not `AFK`

#### Scenario: Excludes AFK sessions
- **WHEN** `SessionService.countActive()` is called while some sessions are AFK
- **THEN** the returned count excludes those AFK sessions

### Requirement: Returning player data is never silently discarded

`SessionRepository` SHALL NOT silently substitute a fresh zeroed `SessionData` when a returning player's stored row exists but cannot be read or parsed. `get(uuid)` SHALL return a fresh `SessionData` only for uuids with no existing row; for an existing row that fails to load, the failure SHALL be logged (including the uuid and error) and the previously cached `SessionData` SHALL be preserved so the player's level is not reset.

#### Scenario: Fresh player initializes normally
- **WHEN** a `PlayerJoin` event fires for a uuid with no row in the `sessions` table
- **THEN** `SessionService.put(Player)` creates a session backed by a fresh `SessionData` and the player starts at level 1

#### Scenario: Corrupt row does not reset level
- **WHEN** a `PlayerJoin` event fires for a uuid whose stored row exists but fails to deserialize
- **THEN** the error is logged with the uuid, the previously cached `SessionData` is kept if present, and the player is not silently reset to level 1