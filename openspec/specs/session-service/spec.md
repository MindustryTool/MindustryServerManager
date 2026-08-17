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

`setLogin` SHALL handle admin promotion/demotion through `Vars.netServer.admins`, update `session.login`, reset the player's admin flag, refresh the player name, and send the `/admin` hint when applicable.

#### Scenario: Login applied to session
- **WHEN** `setLogin(session, login)` is called with an admin login
- **THEN** the target player is administered, `session.login` is set, the player's name is refreshed, and the `/admin` toggle hint is sent

#### Scenario: Level recomputed on update
- **WHEN** `update(session)` runs and the computed level differs from `session.currentLevel`
- **THEN** `session.currentLevel` and the player name are updated
- **AND** a level-up message is broadcast to all players on level increase