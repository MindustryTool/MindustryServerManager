# exp-system

## Purpose

TBD - created by syncing change rework-exp-system. Update Purpose after implementation.

## Requirements

### Requirement: Stored exp counter

The plugin SHALL store each player's total exp as an explicit counter in `SessionData.exp` (a `float`, allowing fractional `expGainBonus` gains to accumulate), persisted inside the existing `sessions.data` JSON blob in the `sessions` table.

Total exp SHALL be read directly from the stored `SessionData.exp` counter (no separate accessor). Exp SHALL no longer be derived from play time (`data.playTime` / `sessionPlayTime`), and `playTimeToExp` SHALL NOT be used to compute level or total exp during normal runtime. `playTimeToExp` is retained solely for one-time migration seeding of `data.exp` from previously accumulated play time.

#### Scenario: Exp is read from the stored counter
- **WHEN** a player's stored counter is `X`
- **THEN** their total exp is `X`, regardless of `data.playTime` or the current session's play time

#### Scenario: Exp persists with session data
- **WHEN** `SessionRepository.write(uuid, data)` saves a session whose `data.exp` is `X`
- **THEN** the JSON stored in `sessions.data` contains `exp = X` and the `totalExp` column is set to `X`

### Requirement: Fractional exp gain bonus accumulates

The plugin SHALL store a player's total exp as a `float` counter so fractional `expGainBonus` gains are preserved. When an exp gain of `amount` is applied to a session whose `expGainBonus` is `B`, the stored `data.exp` SHALL increase by `amount + B * amount` without rounding the bonus away.

#### Scenario: Sub-integer bonus is not dropped
- **WHEN** a session with `expGainBonus` of `0.5` receives an exp gain of `1`
- **THEN** `data.exp` increases by `1.5`, and the fractional part is carried forward into later gains

#### Scenario: Bonus math stays as-is in the gain listener
- **WHEN** `onExpGain` applies an exp gain
- **THEN** `data.exp += amount + expGainBonus * amount` is used unchanged (no integer rounding)

### Requirement: Centralized exp gain via ExpGainEvent

The plugin SHALL add exp to a player's stored counter only through a single event mechanism: firing `ExpGainEvent(session, amount)` (defined in `plugin.session`, carrying the `Session` and a `long` amount). A single listener SHALL be the only place that mutates the exp counter: it applies the event amount, marks the session dirty, and recomputes the player's level.

#### Scenario: Firing the event grants exp
- **WHEN** `ExpGainEvent(session, X)` is fired
- **THEN** the session's stored `data.exp` increases by `X`, the session is marked dirty, and the player's level is recomputed

### Requirement: Per-second exp accumulation

While a player is online, the plugin SHALL increment that player's stored exp counter by 1 once per second.

`SessionService` SHALL fire `ExpGainEvent(session, 1)` once per second for every active session via the existing `@Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)` task; the centralized `ExpGainEvent` listener SHALL apply the amount to the stored counter.

#### Scenario: Online time grants exp
- **WHEN** a player stays online for `N` seconds
- **THEN** the per-second task fires `ExpGainEvent(session, 1)` `N` times and their `data.exp` counter increases by `N`

#### Scenario: Exp increments are persisted
- **WHEN** the session data is written to the database
- **THEN** the `totalExp` column and the `exp` field inside `sessions.data` reflect all exp gained during the session

### Requirement: Level derived from the stored exp counter

The player's level SHALL be computed exclusively from the stored exp counter via `ExpUtils.levelFromTotalExp(data.exp)`. Level-up messages, player name formatting, and the `/me` info string SHALL all reflect the stored counter.

#### Scenario: Level reflects accumulated counter
- **WHEN** a player's `data.exp` reaches a value that maps to a higher level
- **THEN** `SessionService.update` raises `session.currentLevel`, updates the player name, and broadcasts the level-up message

#### Scenario: Info string uses stored exp
- **WHEN** a player runs `/me`
- **THEN** the displayed total exp and level come from the stored `data.exp` counter

### Requirement: Migration preserves existing progress

On startup, the plugin SHALL run a one-time migration (gated by a `Core.settings` flag, `EXP_RECALCULATED_5`) that seeds each existing player's `data.exp` from their previously accumulated play time (`playTimeToExp(data.playTime)`) and recomputes the `totalExp` column so no player loses progress. The migration SHALL only touch rows whose stored exp counter is missing or zero while `playTime` is greater than zero, leaving already-seeded rows untouched.

#### Scenario: Existing players keep their exp
- **WHEN** the migration runs for a session whose prior play time was `T` ms and whose stored `data.exp` is zero
- **THEN** `data.exp` is set to `T / 1000` and `totalExp` equals that seeded value

#### Scenario: Already-seeded rows are not overwritten
- **WHEN** the migration runs for a session whose stored `data.exp` is already greater than zero
- **THEN** the stored `data.exp` and `totalExp` are left unchanged

#### Scenario: Migration runs only once
- **WHEN** the plugin starts after the migration has completed
- **THEN** the migration is skipped because the `EXP_RECALCULATED_5` settings flag is set

### Requirement: No silent level reset on unreadable data

The plugin SHALL NOT reset a returning player's level to 1 when their stored session row cannot be read or parsed. `SessionRepository.get(uuid)` SHALL distinguish a genuinely new player (no row) from an existing row that fails to load, and SHALL surface load/parse failures loudly (log with the uuid and error) instead of silently returning a zeroed `SessionData`.

#### Scenario: Fresh player gets a zeroed session
- **WHEN** `SessionRepository.get(uuid)` is called for a uuid with no row in the `sessions` table
- **THEN** it returns a fresh `SessionData` with `exp = 0` and no error is raised

#### Scenario: Unreadable row is logged, not wiped
- **WHEN** `SessionRepository.get(uuid)` is called for a uuid whose stored row exists but whose JSON fails to deserialize or whose `data` is empty
- **THEN** the failure is logged with the uuid and error, and the previously cached `SessionData` (if any) is preserved rather than silently replaced with a zeroed one