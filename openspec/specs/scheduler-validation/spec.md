# scheduler-validation

## Purpose
Defines defensive up-front validation for `@Schedule` annotation intervals, including fail-fast rejection of invalid values, strict validation regardless of dispatch precedence, and initial-delay mapping without clamping.

## Requirements

### Requirement: Fail-fast interval validation

The system SHALL validate `@Schedule` annotation values up-front in `Scheduler.process()` before dispatch and SHALL throw `IllegalArgumentException` with a method-qualified message for: `fixedRate == 0`, `fixedDelay == 0` (and other negative explicit values), and explicit `delay < 0`. The exception message SHALL name the declaring class and method, the offending field, the value, and the `unit`, for example `@Schedule fixedRate must be > 0: Foo.bar fixedRate=0 MILLISECONDS`. The throw SHALL propagate out of `Registry.init()` and prevent server startup. An empty `@Schedule()` (which now defaults `delay` to `0`) SHALL be treated as a valid one-time task that runs immediately.

#### Scenario: Zero fixedRate crashes

- **WHEN** a method is annotated with `@Schedule(fixedRate = 0)`
- **THEN** `Scheduler.process()` throws `IllegalArgumentException` naming the method, field, value, and unit, and `Registry.init()` fails

#### Scenario: Zero fixedDelay crashes on main thread

- **WHEN** a `@MainThread` method is annotated with `@Schedule(fixedDelay = 0)`
- **THEN** `Scheduler.process()` throws `IllegalArgumentException` before any `Timer` registration

#### Scenario: Negative explicit delay crashes without clamping

- **WHEN** a method is annotated with `@Schedule(delay = -5)`
- **THEN** `Scheduler.process()` throws `IllegalArgumentException` and no clamping applies

#### Scenario: Explicit delay minus one crashes

- **WHEN** a method is annotated with `@Schedule(delay = -1)`
- **THEN** `Scheduler.process()` throws `IllegalArgumentException` because `delay` now defaults to `0` and any negative is invalid

#### Scenario: Empty annotation runs immediately

- **WHEN** a method is annotated with `@Schedule()` and nothing is set (`delay` defaults to `0`)
- **THEN** `Scheduler.process()` schedules a one-time task with `0` delay and does not throw

### Requirement: Strict up-front validation with unchanged precedence

The system SHALL validate all explicitly-set `@Schedule` fields up-front and SHALL throw if any is invalid, even when another field would take precedence. Dispatch precedence SHALL remain `fixedRate > fixedDelay > delay`, `-1` SHALL continue to mean unset for `fixedRate`/`fixedDelay`, `delay` SHALL default to `0`, and `delay == 0` for one-time scheduling SHALL remain valid (run immediately).

#### Scenario: Invalid non-selected field still crashes

- **WHEN** a method is annotated with `@Schedule(fixedRate = -5, fixedDelay = 5, unit = TimeUnit.SECONDS)`
- **THEN** `Scheduler.process()` throws `IllegalArgumentException` instead of silently scheduling the `fixedDelay` branch

#### Scenario: One-time delay zero remains valid

- **WHEN** a method is annotated with `@Schedule(delay = 0)`
- **THEN** the task is scheduled with no initial delay and `Scheduler.process()` does not throw

### Requirement: Initial-delay mapping

The system SHALL remove `Math.max(0, delay)` clamping from the annotation path. An unset `delay` (now `0` by default) when a `fixedRate` or `fixedDelay` branch runs SHALL mean `0` initial delay. Explicit negative delays SHALL already have been rejected by up-front validation and SHALL NOT be clamped.

#### Scenario: Unset initial delay maps to zero

- **WHEN** a method is annotated with `@Schedule(fixedDelay = 5, unit = TimeUnit.SECONDS)` and `delay` is unset
- **THEN** the periodic task is scheduled with `0` initial delay and the configured period
