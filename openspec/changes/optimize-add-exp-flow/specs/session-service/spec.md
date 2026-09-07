## MODIFIED Requirements

### Requirement: Session business logic preserved

`SessionService` SHALL retain the business logic from the former `SessionService`: `update(Session)`, `setLogin(Session, LoginDto)`, and the `getLevel` function computing level from total exp via `ExpUtils`.

`getLevel` SHALL compute the player's level exclusively from the stored exp counter: `ExpUtils.levelFromTotalExp(data.exp)`, where `data` is the session's `SessionData`.

`update(Session)` SHALL be split into two responsibilities: a per-second `@Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)` task SHALL fire `ExpGainEvent(session, 1)` for every active session, and a single `onExpGain(ExpGainEvent)` listener SHALL apply the event amount to `session.getData().exp` non-blockingly without monitor synchronization, mark the session dirty via `sessionRepository.markDirty(session)`, and recompute the player's level; level-up broadcasts and name updates fire only when a level change is detected.

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
- **THEN** the `onExpGain` listener increments `session.getData().exp` by 1 via a non-blocking update, marks the session dirty, and recomputes the player's level

#### Scenario: Name update suppressed on unchanged level
- **WHEN** `onExpGain` executes and the recomputed level equals `session.currentLevel`
- **THEN** `session.player.name(...)` is not invoked and no name sync packets are dispatched across the network

#### Scenario: Level derives from stored counter
- **WHEN** `getLevel.apply(session)` runs
- **THEN** the returned level is computed from the stored `data.exp` counter via `ExpUtils`, independent of play time
