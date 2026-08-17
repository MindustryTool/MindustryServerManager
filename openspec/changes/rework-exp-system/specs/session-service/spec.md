## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Join-time daily login bonus handling

The daily login process SHALL NOT be hardcoded in `SessionService`. It SHALL be handled by a `DailyService` `@Component` listening to `SessionCreatedEvent`: on session creation, the player's last login date is checked, and on a new-day login the plugin SHALL fire `ExpGainEvent(session, 3600)` and notify the player, while a first-ever login grants no bonus. `SessionService.put(Player)` SHALL only create the session and fire `SessionCreatedEvent`.

#### Scenario: Session creation runs daily bonus check
- **WHEN** a `PlayerJoin` event creates a session and `SessionCreatedEvent` fires for a returning player on a new day
- **THEN** the `DailyService` listener fires `ExpGainEvent(session, 3600)` (granting the exp through the centralized listener), the login date is updated, and the daily bonus message is sent

#### Scenario: Session creation skips first-ever bonus
- **WHEN** a `PlayerJoin` event creates a session and `SessionCreatedEvent` fires for a player with no prior login record
- **THEN** a login record is created and no exp bonus or message is granted