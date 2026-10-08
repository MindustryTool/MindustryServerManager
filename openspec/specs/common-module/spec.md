# common-module

## Purpose

Shared cross-cutting module hosting reusable primitives and shared wire/event models consumed by multiple Gradle modules. Provides the keyed token-bucket rate limiter backing translation throttling, plus the shared domain models under `common.<domain>` packages.

## Requirements

### Requirement: Shared Common Module
The build SHALL include a `common` Gradle module that does not depend on any other project module (`plugin`, `server`, `gateway`, `database`, `annotation`), and both the `plugin` and `server` modules SHALL depend on `:common`. The `common` module SHALL host all shared wire/event models under `common.<domain>` packages, and the build SHALL NOT include a `dto` module.

#### Scenario: Module included in the build
- **WHEN** the Gradle build is evaluated
- **THEN** the `common` module is included in `settings.gradle.kts`, the `dto` module is absent, and `common` compiles independently of other project modules

#### Scenario: No dependency cycle
- **WHEN** the `common` module's dependencies are inspected
- **THEN** it declares no dependency on `plugin`, `server`, `gateway`, `database`, or `annotation`

#### Scenario: Consumers use common
- **WHEN** the `server` and `plugin` module dependencies are inspected
- **THEN** neither declares `project(":dto")` and both declare a dependency on `:common`

### Requirement: Keyed Token Bucket Rate Limiter
The `common` module SHALL provide a `TokenBucket` with a configurable capacity and refill rate, whose `tryAcquire` consumes one token when available and refills tokens based on elapsed monotonic time, and a `KeyedRateLimiter<K>` that lazily creates one `TokenBucket` per key, is safe for concurrent use, and automatically evicts keys that have been idle beyond a configured duration. The limiter SHALL also expose an explicit `evictIdle(Duration)` operation.

#### Scenario: Burst then sustained rate
- **WHEN** a `TokenBucket` with capacity 5 and refill 1 per second is acquired 5 times in immediate succession
- **THEN** the first 5 acquisitions succeed and the 6th fails within the same second

#### Scenario: Refill after elapsed time
- **WHEN** more than one second has elapsed since a bucket was exhausted
- **THEN** a subsequent acquisition succeeds

#### Scenario: Keys are isolated
- **WHEN** one key exhausts its bucket
- **THEN** acquisitions for a different key still succeed

#### Scenario: Idle keys are evicted automatically
- **WHEN** a key has been idle beyond the configured idle duration and another acquisition triggers a sweep
- **THEN** the idle key's bucket is removed, and a later acquisition for that key starts with a full bucket

#### Scenario: Explicit idle eviction
- **WHEN** `evictIdle` is called with a duration shorter than a key's idle time
- **THEN** that key's bucket is removed

#### Scenario: Concurrent acquisitions are safe
- **WHEN** multiple threads acquire tokens for the same key concurrently
- **THEN** no more than the bucket capacity succeed in the burst window and no corruption occurs

### Requirement: Shared domain models live in common
The `common` module SHALL provide the moved shared models in their agreed domain packages with Jackson / Lombok / Mindustry `compileOnly` support, and wire JSON field names SHALL match the pre-move shapes.

#### Scenario: Domain package homes
- **WHEN** the moved types are inspected
- **THEN** content types (`ManagerMap`, `ManagerMod`, `MapMetadata`, `Mod`, `ModMetadata`) live in `common.content`, player types (`Login`, `LoginRequest`, `PlayerInfo`, `PlayerRecord`, `PlayerRecordPage`, `RecentPlayer`, `TeamInfo`) in `common.player`, server types (`Server`, `ServerSnapshot`, `ServerConfig`, `ServerConfigMessage`, `ServerMetadata`, `ServerStatus`, `StartServer`, `ServerFile`, `ServerCommand`, `CommandParam`) in `common.server`, translation types in `common.translation`, events (`BaseEvent`, `ServerEvents`) in `common.event`, `NodeRemoveReason` and `ApiServer` in `common.network`, and `Pair` plus `ErrorResponse` in `common.util`

#### Scenario: Clash-driven names applied
- **WHEN** the renamed types are inspected
- **THEN** `ServerConfigMessage`, `MapMetadata`, `TeamInfo`, `ModMetadata`, `PlayerInfo`, `PlayerRecord`, and `ServerSnapshot` exist under the packages above and no type named `dto`, `Dto`, `HostDto`, or `PlayerMetadata` remains in the codebase

#### Scenario: Wire shapes unchanged
- **WHEN** the moved models are serialized with Jackson
- **THEN** field names and `@JsonProperty` requirements match the pre-move `dto`-module shapes and `ServerEvents` inner `*Event` names still derive the same wire strings (`server-state`, `start`, `stop`, `log`, `chat`, `player-join`, `player-leave`, `player-ban`, `player-kick`, `player-vote-kick`)

#### Scenario: Dead types removed
- **WHEN** the codebase is searched
- **THEN** `HostDto` and `PlayerMetadata` have zero definitions and zero imports
