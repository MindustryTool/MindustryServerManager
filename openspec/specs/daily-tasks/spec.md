# daily-tasks Specification

## Purpose
TBD - created by archiving change add-money-system. Update Purpose after archive.
## Requirements
### Requirement: Gamemode-dependent daily task definitions

The plugin SHALL provide a daily task architecture using discrete `GamemodeTaskProvider` strategies that supply daily objectives tailored to the active server gamemode:
- `TowerDefenseTaskProvider`: tasks for surviving waves, building turrets, and destroying enemy units.
- `FloodTaskProvider`: tasks for surviving flood stages, building infrastructure, and power generation.
- `CataliTaskProvider`: tasks for defeating rival team units and capturing sectors.
- `DefaultTaskProvider`: fallback general tasks for mining items and surviving waves.

Each daily task SHALL define a unique ID, descriptive display text (localized or key-based), a numeric goal, and a coin reward amount. `DailyTaskService` SHALL resolve tasks by delegating to the appropriate provider based on the active gamemode.

#### Scenario: Server running Tower Defense provides TD tasks
- **WHEN** the server is running the Tower Defense gamemode
- **THEN** the active daily task set contains Tower Defense specific objectives provided by `TowerDefenseTaskProvider`

#### Scenario: Server running Flood provides Flood tasks
- **WHEN** the server is running the Flood gamemode
- **THEN** the active daily task set contains Flood specific objectives provided by `FloodTaskProvider`

### Requirement: Daily task progress tracking and rollover

The plugin SHALL track player task progress in `SessionData.dailyTaskProgress` (mapping task ID to current count) and store the last task active date in `SessionData.dailyTaskDate`.

When a player joins or a new day begins:
- If `dailyTaskDate` differs from the current server calendar day (`LocalDate.now()`), the player's progress SHALL reset to 0, and `dailyTaskDate` SHALL be updated to today.
- If `dailyTaskDate` equals the current server calendar day, existing progress SHALL be preserved.

#### Scenario: Daily progress resets on new calendar day
- **WHEN** a player joins on a new day
- **THEN** their `dailyTaskProgress` is cleared, `dailyTaskDate` is set to today's date, and progress starts from 0

#### Scenario: Progress retained across reconnects on the same day
- **WHEN** a player reconnects on the same day
- **THEN** their existing `dailyTaskProgress` values are restored

### Requirement: Task completion and reward distribution

When in-game events increment a player's task progress to reach or exceed the task goal, the plugin SHALL mark the task as completed in `dailyTaskProgress`, credit the defined coin reward via `MoneyService`, and send a congratulatory notification. The task cannot be completed or rewarded again on the same day.

#### Scenario: Player completes daily task
- **WHEN** a player achieves the required goal count for a daily task
- **THEN** the task is marked complete, the coin reward is deposited into their wallet, and a completion message is sent

#### Scenario: No double reward on already completed task
- **WHEN** further event progress occurs for a task already marked complete
- **THEN** no additional coins or completion notifications are awarded

### Requirement: Daily task menu and command

The plugin SHALL provide a `/tasks` command and interactive `TaskMenu` dialog displaying today's active tasks, current progress bars, coin rewards, and completion status. All dialog texts and status indicators SHALL use `Tr.t` localization and font-safe `Iconc` characters.

#### Scenario: Player views tasks via command
- **WHEN** a player executes `/tasks`
- **THEN** the tasks dialog opens showing the current gamemode's daily tasks, progress, and rewards with localized text and font-safe icons

### Requirement: Daily task anti-abuse debouncing

The plugin SHALL enforce a minimum interval of 500ms between block construction task progress increments per player to prevent automated build-deconstruct macro exploits.

#### Scenario: Rapid block placement throttled
- **WHEN** a player places multiple blocks within less than 500ms
- **THEN** only the first block increments task progress and subsequent builds within the window are ignored

### Requirement: Administrative daily task reset

The plugin SHALL expose an API method `resetTasks(Session session)` in `DailyTaskService` allowing administrators or system tasks to clear a player's daily task progress.

#### Scenario: Resetting task progress
- **WHEN** `resetTasks(session)` is called
- **THEN** `session.getData().dailyTaskProgress` is cleared
- **AND** the session is marked dirty in `SessionRepository`

