# common-module

## Purpose

Shared cross-cutting module hosting reusable primitives consumed by multiple Gradle modules. Currently provides the keyed token-bucket rate limiter backing translation throttling.

## Requirements

### Requirement: Shared Common Module
The build SHALL include a `common` Gradle module that does not depend on any other project module (`plugin`, `server`, `gateway`, `dto`, `database`, `annotation`), and both the `plugin` and `server` modules SHALL depend on `:common`.

#### Scenario: Module included in the build
- **WHEN** the Gradle build is evaluated
- **THEN** the `common` module is included in `settings.gradle.kts` and compiles independently of other project modules

#### Scenario: No dependency cycle
- **WHEN** the `common` module's dependencies are inspected
- **THEN** it declares no dependency on `plugin`, `server`, `gateway`, `dto`, `database`, or `annotation`

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
