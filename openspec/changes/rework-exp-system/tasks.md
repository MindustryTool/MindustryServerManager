## 1. Stored exp counter

- [x] 1.1 Add `public long exp = 0;` field to `SessionData` (`plugin/src/main/java/plugin/session/SessionData.java`)
- [x] 1.2 Remove `ExpUtils.getTotalExp` entirely; exp is read directly from the stored `data.exp` counter in `ExpUtils.java`
- [x] 1.3 Update `ExpUtils.getLevel(Session)` to use `session.getData().exp` directly
- [x] 1.4 Update `SessionService.getLevel` (`plugin/src/main/java/plugin/session/SessionService.java`) to compute the level from `data.exp`, dropping the `sessionPlayTime` term
- [x] 1.5 Update `SessionRepository.write` and `recalculateAllTotalExp` to compute `totalExp` from `data.exp` directly

## 2. Centralized ExpGainEvent

- [x] 2.1 Create `plugin.session.ExpGainEvent` with `Session session` and `long amount` fields, mirroring `LevelUpEvent`'s style (distinct from the team-based `plugin.gamemode.catali.event.ExpGainEvent`)
- [x] 2.2 In `SessionService`, extract the level recompute/name/`LevelUpEvent` broadcast body of `update(Session)` into an `updateLevel(Session)` helper
- [x] 2.3 Add a `@Listener onExpGain(ExpGainEvent)` in `SessionService` that `synchronized (sessionData)` applies `event.amount` to `session.getData().exp`, calls `sessionRepository.markDirty(session)`, then calls `updateLevel(event.session)`
- [x] 2.4 Repurpose the `@Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)` task to `each(s -> PluginEvents.fire(new ExpGainEvent(s, 1)))` instead of mutating exp inline
- [x] 2.5 Ensure no other code path mutates `SessionData.exp` directly; all additions go through `ExpGainEvent`

## 3. Daily login tracking table and repository

- [x] 3.1 Create `DailyRepository` `@Component` in `plugin/src/main/java/plugin/session/` with `@Init` creating `player_logins (uuid TEXT PRIMARY KEY, last_login_date TEXT NOT NULL)`
- [x] 3.2 Implement `Optional<String> getLastLogin(String uuid)` in `DailyRepository` using `DB.prepare`
- [x] 3.3 Implement `void setLastLogin(String uuid, String date)` in `DailyRepository` using an upsert (`INSERT ... ON CONFLICT(uuid) DO UPDATE`)

## 4. Daily login bonus via DailyService

- [x] 4.1 Create `DailyService` `@Component` in `plugin/src/main/java/plugin/session/` with constructor-injected `DailyRepository` and a `@Listener onSessionCreated(SessionCreatedEvent)` handler
- [x] 4.2 In the handler, with today's `LocalDate.now().toString()`: return no-op if a record exists with the same date
- [x] 4.3 In the handler: if no record exists, call `setLastLogin` with today's date and grant no bonus (first-ever login)
- [x] 4.4 In the handler: if a record exists with a different date, call `setLastLogin` with today's date, send the player a daily login bonus message (e.g. via `I18n.t(session, ...)`), and fire `PluginEvents.fire(new ExpGainEvent(session, 3600))`
- [x] 4.5 Ensure `SessionService.put(Player)` contains no daily-login logic; the bonus is triggered solely by the `SessionCreatedEvent` the handler listens to, using the stored `3600` bonus constant

## 5. Info string and play-time display

- [x] 5.1 Update `SessionUtils.getInfoString` to read exp from the stored `data.exp` counter instead of `data.playTime + session.sessionPlayTime()`
- [x] 5.2 Update the exp label in the `/me` info string to display the stored counter exp rather than `ExpUtils.playTimeToExp(...)` (keep the play-time hours/minutes display)

## 6. Migration for existing players

- [x] 6.1 Add a one-time migration in `SessionRepository.init` gated by a new `Core.settings` flag (e.g. `EXP_RECALCULATED_4`) that seeds `data.exp = playTimeToExp(data.playTime)` for every existing `sessions` row, writes the data back, and recomputes the `totalExp` column
- [x] 6.2 Verify the migration only runs once and `Log.err` failures like the existing `EXP_RECALCULATED_2/3` blocks

## 7. Verification

- [x] 7.1 Search `plugin/src` for remaining call sites of `getTotalExp` / `playTimeToExp` level math and fix any that remain
- [x] 7.2 Confirm all exp additions (per-second tick, daily bonus) fire `ExpGainEvent` and only `onExpGain` mutates `SessionData.exp`
- [x] 7.3 Build the plugin (gradle) and confirm compilation succeeds
- [x] 7.4 Manually verify: player gains 1 exp/sec online, `/me` shows stored exp, daily bonus +3600 with message on new-day login, no bonus on first login, leaderboard/rank totals unchanged after migration