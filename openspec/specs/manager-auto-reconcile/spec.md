# manager-auto-reconcile

## Purpose

Manager-owned auto reconcile of desired config and the bundled plugin jar against live nodes, acting only when the player list is empty. Synced from change manager-auto-reconcile-plugin-config.
## Requirements
### Requirement: Reconcile drift only when empty

The manager SHALL diff full desired config plus mods plus bundled jar hash against the live box and SHALL act only when the live player list is empty. Drift SHALL be any of: port, cpu, memory, image, env, hub flag, mode, host command, name, description, auto-turn-off flag, a missing or deleted mod, or a plugin jar hash difference. The manager SHALL resolve all drift with a single recreate. The rule SHALL be the same for hubs and game nodes. When players are online the manager SHALL mark pending and retry on a later tick, waiting forever with no force kick.

#### Scenario: Empty with any drift recreates
- **WHEN** any config, mod, or jar hash drift exists and players are empty
- **THEN** the manager recreates the box (remove plus create plus host), rewriting the label and the bundled jar

#### Scenario: Hot fields recreate so drift clears
- **WHEN** mode, host command, name, or description differs from the stored wish and players are empty
- **THEN** the manager recreates, updating the container label so the drift is not re-detected

#### Scenario: Drift with players waits
- **WHEN** any drift exists and players are online
- **THEN** the manager takes no destructive action and records pending

#### Scenario: No drift stays idle
- **WHEN** wish equals live and jar hashes match
- **THEN** the manager takes no action and clears pending

### Requirement: Chain recreate before auto turn-off

The manager SHALL check drift before auto turn-off on each tick. Recreate wins over remove. A drifted empty node SHALL recreate first and reset its empty timer, then become eligible for turn-off only after 20 minutes continuous empty. `isAutoTurnOff=false` nodes SHALL skip idle remove but SHALL still recreate on drift. Remove SHALL use time, not tick count: `now minus since >= 20 minutes`.

#### Scenario: Drifted empty node recreates not removes
- **WHEN** a node is empty and drifted with any empty time
- **THEN** the manager recreates it and resets `since` instead of removing it

#### Scenario: Clean empty node removed after 20 minutes
- **WHEN** a node has no drift and stays empty for 20 minutes continuous
- **THEN** the manager removes it with `NO_PLAYER`

#### Scenario: Players reset empty timer
- **WHEN** players join a pending empty node
- **THEN** the manager clears pending and resets `since` with no remove

#### Scenario: Opt-out node still recreates
- **WHEN** `isAutoTurnOff` is false and drift exists and players are empty
- **THEN** the manager recreates and never removes for idle

### Requirement: Live hash in state and single reconcile state

The plugin `get-state` snapshot SHALL include the running `plugin.jar` SHA-256. The manager SHALL keep one reconcile status per server with a `Phase` (`IDLE`, `PENDING`, `ACTING`, `REMOVING`), an empty timer `since`, and a fail timer `failingSince`. The manager SHALL NOT keep the old `serverFlags` map, the `ServerFlag` enum, a per-tick act flag, or a separate turn-off scan. One tick SHALL do one `state()` fetch and one empty check shared by drift and turn-off paths. The manager SHALL NOT take the per-server host lock inside reconcile.

#### Scenario: Hash visible in state
- **WHEN** the manager queries `get-state`
- **THEN** the snapshot carries the running jar hash for diffing

#### Scenario: Phase reflects the outcome
- **WHEN** a tick finds drift, acts, or removes
- **THEN** the status `Phase` is `PENDING`, `ACTING`, or `REMOVING` respectively, and `IDLE` when no action is needed

### Requirement: Single locked recreate action

The manager SHALL resolve every drift kind with one recreate that runs under the per-server lock shared with `host()`. `host()` SHALL be split into an unlocked `hostLocked` and a locking wrapper. The recreate SHALL run as `lock -> remove(CONFIG_DRIFT) -> hostLocked(wish) -> unlock` so backend host calls and reconcile never race, and reconcile SHALL NOT hold a lock of its own.

#### Scenario: Backend and reconcile serialize
- **WHEN** a backend `host-server` and a reconcile recreate target the same server
- **THEN** they run one after the other on the same lock and never overlap

#### Scenario: Recreate uses the config-drift reason
- **WHEN** reconcile recreates a drifted server
- **THEN** the removal uses `CONFIG_DRIFT`

### Requirement: Unreachable container reclamation

When `state()` reports a running container as unreachable (`DISCONNECT`, `NOT_RESPONSE`, or `UNSET`), the manager SHALL set `failingSince` once and, once 5 minutes have elapsed, SHALL reclaim it: recreate with reason `NOT_RESPONSE` when `isAutoTurnOff` is false and a stored wish exists, otherwise remove with reason `NOT_RESPONSE`. A reachable snapshot SHALL clear `failingSince`. Reclamation SHALL ignore the auto-turn-off opt-out as a protection from removal.

#### Scenario: Unreachable opt-out node is revived
- **WHEN** an `isAutoTurnOff=false` running node stays unreachable for 5 minutes and a wish exists
- **THEN** the manager recreates it with reason `NOT_RESPONSE`

#### Scenario: Unreachable auto-off node is removed
- **WHEN** an auto-turn-off running node stays unreachable for 5 minutes
- **THEN** the manager removes it with reason `NOT_RESPONSE`

#### Scenario: Recovery clears the fail timer
- **WHEN** an unreachable node reports a reachable snapshot on a later tick
- **THEN** `failingSince` is cleared and no reclamation occurs

### Requirement: Typed drift classification

`ServerMisMatch` SHALL carry an internal `MisMatchType` enum covering `PORT`, `CPU`, `MEMORY`, `IMAGE`, `ENV`, `HUB`, `MODE`, `HOST_COMMAND`, `NAME`, `DESCRIPTION`, `AUTO_TURN_OFF`, `MOD_MISSING`, `MOD_DELETED`, and `PLUGIN_JAR`. Classification SHALL read the enum rather than matching `field` substrings. The wire payload for `get-mismatch` SHALL continue to carry only the `field` string so backend and UI are unchanged. `AUTO_TURN_OFF`, `NAME`, `DESCRIPTION`, and mod mismatches SHALL be included in the diff.

#### Scenario: Classification by enum
- **WHEN** the manager classifies a mismatch
- **THEN** it reads `MisMatchType`, not the `field` text

#### Scenario: Wire stays string-only
- **WHEN** `get-mismatch` returns mismatches to the backend
- **THEN** each mismatch exposes only the `field` string and not the enum type

