## Why

The current exp system derives total exp from cumulative play time (`data.playTime + sessionPlayTime`). This makes exp a derived value, so players get exp only while their session data is being saved, and there is no reward for returning to the server on subsequent days. We want exp to be an explicit, persistent counter that accrues in real time and a daily login bonus that rewards regular players.

## What Changes

- Exp is now an explicit stored value: `SessionData.exp` is incremented by **1 every second** while a player is online, instead of being derived from session play time.
- All exp additions are centralized through a new session-level `ExpGainEvent` (session + amount); a single listener applies the exp, marks the session dirty, and recomputes the level.
- `totalExp` persisted in the `sessions` table is the stored exp counter (play-time-derived exp is removed).
- Add a new database table `player_logins` to track the last login date per player.
- On player join, if the player already has a login record and the last login date differs from today, grant a **3600 exp** daily bonus, update the login date, and send a message to the player.
- On the **first-ever login** (no existing login record), create the login record but do **not** grant the daily bonus.
- Level, level-up, player name, and `/me` info are recomputed from the stored exp counter.

## Capabilities

### New Capabilities
- `exp-system`: Stored, per-second exp accumulation (1 exp/sec) replacing play-time-derived exp, including how total exp, level, and level-up derive from the stored counter.
- `daily-login-bonus`: New `player_logins` table tracking daily logins, granting a 3600-exp bonus with a player message on each new-day login, and skipping the bonus on a player's first-ever login.

### Modified Capabilities
- `session-service`: `SessionData` gains an `exp` field; session join now triggers daily login bonus handling; `/me` info and level updates use the stored exp counter.

## Impact

- `plugin/src/main/java/plugin/session/SessionData.java` - add persistent `exp` field.
- `plugin/src/main/java/plugin/session/ExpUtils.java` - `getTotalExp` reads the stored counter; `playTimeToExp` no longer drives level.
- `plugin/src/main/java/plugin/session/SessionService.java` - per-second exp increment, daily bonus on join.
- `plugin/src/main/java/plugin/session/SessionRepository.java` - persist `totalExp` from stored counter; add `player_logins` table.
- `plugin/src/main/java/plugin/session/SessionUtils.java` - `/me` info string reflects stored exp.
- New `DailyRepository` (DB access for `player_logins`) and `DailyService` (listener on `SessionCreatedEvent`) under `plugin.session`, replacing any hardcoded join-time logic in `SessionService`.