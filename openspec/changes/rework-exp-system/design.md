## Context

Exp is currently a derived value: `ExpUtils.getTotalExp(data, sessionPlayTime)` computes `playTimeToExp(data.playTime + sessionPlayTime)` = 1 exp per 1000ms of cumulative play time. `SessionData.playTime` (ms) is persisted inside the `sessions.data` JSON blob, and `totalExp` is stored as a column for leaderboard/rank lookups. Level is recomputed every second by `SessionService.update()` via `@Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)`.

Because exp is derived from play time, it only advances when session data is saved (on leave or the 10s flush), and there is no daily-login incentive. This change makes exp an explicit counter that advances every second while online and adds a daily login bonus tracked in a new table.

## Goals / Non-Goals

**Goals:**
- Store exp as an explicit persistent counter (`SessionData.exp`) advanced by 1 every second while a player is online.
- Keep `totalExp` column semantics so leaderboard and rank queries keep working unchanged.
- Track daily logins in a new `player_logins` table and grant a 3600-exp daily bonus (with a player message) on each new-day login, except a player's very first login ever.
- Preserve existing player progress through a one-time migration.

**Non-Goals:**
- No change to the level formula (`levelFromTotalExp`), level-up broadcast, or name formatting.
- No change to AFK handling; all online sessions earn 1 exp/sec regardless of AFK state, matching current behavior where play time accrues regardless of AFK.
- No real translation/localization bundles; messages follow the existing `I18n.t` convention used elsewhere (literal text).

## Decisions

### 1. Exp is a stored counter, not derived from play time
Add `public long exp` to `SessionData`. It serializes with the existing `sessions.data` JSON. Total exp is read directly from `data.exp` everywhere; the previous `ExpUtils.getTotalExp` accessor is removed, and the `sessionPlayTime`-based term is gone. `playTimeToExp` remains only for the one-time migration seeding.

**Rationale:** Exp becomes the single source of truth. The 1/sec increment happens in the already-existing per-second `SessionService.update()` schedule, so no new scheduler is needed.
**Alternative considered:** keeping a `sessionPlayTime` term and only switching the base — rejected because it keeps exp coupled to the session clock and complicates persistence.

### 2. All exp additions are centralized through a new `ExpGainEvent`
Create `plugin.session.ExpGainEvent` carrying `Session session` and `long amount`. A single `@Listener` in `SessionService` (`onExpGain`) is the only place that writes to the exp counter: it applies `event.amount` under `synchronized (sessionData)`, marks the session dirty, and recomputes the player's level via a helper `updateLevel(Session)` (the level/name/level-up logic extracted from the old `update()`). Every exp source fires the event instead of mutating exp directly:
- per-second tick: `@Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)` → `each(s -> PluginEvents.fire(new ExpGainEvent(s, 1)))`;
- daily login bonus: `PluginEvents.fire(new ExpGainEvent(session, 3600))`.

**Rationale:** One code path owns exp mutation, dirty-marking, and level refresh; future exp sources only need to fire the event.
**Alternative considered:** adding exp inline at each site — rejected, it duplicates mutation/level-up logic and is error-prone. Note: the existing `plugin.gamemode.catali.event.ExpGainEvent` is team-based and unrelated to player session exp, so a new session-level event lives in `plugin.session` to avoid a semantic clash.

### 3. New `player_logins` table and `DailyRepository`
New table:
```sql
CREATE TABLE IF NOT EXISTS player_logins (
    uuid            TEXT PRIMARY KEY,
    last_login_date TEXT NOT NULL
);
```
`last_login_date` is `LocalDate.now().toString()` (server-local day). New `DailyRepository` `@Component` (mirroring `SessionRepository` patterns) exposes `getLastLogin(String uuid)` returning `Optional<String>`, `setLastLogin(String uuid, String date)`, and creates the table in `@Init`.

**Rationale:** A dedicated repository keeps DB access in one place and follows the existing `SessionRepository`/`DB.prepare` pattern. Date string (not epoch millis) matches the "once per calendar day" semantics and is human-readable.
**Alternative considered:** storing an epoch-day `long` — rejected, a date string is simpler to inspect and just as easy to compare.

### 4. Daily process runs on `SessionCreatedEvent` via a `DailyService`
The daily login process lives in a dedicated `DailyService` `@Component` (owning `DailyRepository`) with a `@Listener onSessionCreated(SessionCreatedEvent)` — `SessionService.put()` does not hardcode it. `SessionCreatedEvent` fires synchronously at the end of `put()`, so the process runs before the player is otherwise interacted with:
1. `getLastLogin(uuid)`:
   - **No record** → `setLastLogin(today)`, grant nothing. (First-ever login.)
   - **Record == today** → do nothing.
   - **Record != today** → `setLastLogin(today)`, send the player the +3600 daily bonus message, and fire `new ExpGainEvent(session, 3600)`.
2. The `onExpGain` listener recomputes the level through `updateLevel(session)`, so the bonus (and any level-up) is reflected immediately. For joins with no bonus, `put()` already ran `updateLevel(session)` during session creation.

**Rationale:** Decouples the daily concern from session lifecycle wiring; `SessionCreatedEvent` is already fired by `put()` and consumed elsewhere (`EventHandler`), so this follows an existing pattern. Synchronous dispatch keeps the bonus applied before the join sequence continues.
**Alternative considered:** hardcoding the check in `SessionService.put()` — rejected, it couples `SessionService` to daily-login logic and bloats the join path.

### 5. Message text
Send via `I18n.t(session, ...)` with literal text, e.g. `I18n.t(session, "@Daily login bonus", "+3600 exp")`. Note: the current `I18n.t` implementation strips the leading `@` and does not resolve bundles, so this produces plain localized-free text, consistent with existing messages like `"Level up"`.

### 6. Migration preserves existing progress
Add a one-time migration gated by a new `Core.settings` flag (following the existing `EXP_RECALCULATED_2/3` pattern, e.g. `EXP_RECALCULATED_4`): for every row in `sessions`, load `SessionData`, set `data.exp = playTimeToExp(data.playTime)` (converting accumulated ms to seconds), write back, then recompute `totalExp = data.exp` and update the column. After migration the leaderboard/rank values are unchanged.

**Rationale:** Prevents existing players from losing progress when the exp source switches from play time to the counter.
**Alternative considered:** dropping `totalExp` and computing on the fly — rejected, it would require reworking `leaderBoard`/`getRank`.

## Risks / Trade-offs

- **Race on concurrent daily-login check** → `SessionService.put()` uses `computeIfAbsent(uuid)`, so only one `SessionCreatedEvent` fires per player; the `player_logins` upsert is on the single-threaded DB executor, so the read-then-write is effectively serialized per uuid.
- **Time-zone interpretation of "day"** → `LocalDate.now()` uses the server's default zone; acceptable since the server defines what "a day" means for all players. Documented behavior.
- **Event fire ordering** → `SessionCreatedEvent` fires synchronously at the end of `put()`; the `DailyService` listener fires `ExpGainEvent` (if bonus) whose `onExpGain` listener recomputes the level, and `put()` also runs `updateLevel(session)` during creation, so the level is correct whether or not a bonus applies.
- **Migration runs while players are online** → runs once at `@Init` before players join in practice; recomputing `totalExp` from the migrated `data.exp` keeps leaderboard consistent even if a session is concurrently dirty.
- **Players who were offline between day changes** → bonus only granted on an actual join on a new day; players can miss a day with no compensation, by design.

## Migration Plan

1. Deploy with the new code; `@Init` creates `player_logins` and runs the `EXP_RECALCULATED_4` backfill.
2. Verify leaderboard/rank values are unchanged after the backfill (they should be, since `data.exp` is seeded from old play-time exp).
3. Rollback: revert code; exp no longer increments, but stored `data.exp`/`totalExp` remain intact and play-time-derived math would resume for new sessions.