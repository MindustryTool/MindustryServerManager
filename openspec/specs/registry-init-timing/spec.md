# registry-init-timing

## Requirements

### Requirement: Measure named operations during init
The Registry SHALL time each major init step by wrapping it in a helper that takes a name and a `Runnable`, runs the operation, and logs the elapsed duration at debug level. Steps SHALL include reflection scan, gamemode setup, filtering, and the component scan loop.

#### Scenario: Init step duration is logged
- **WHEN** `Registry.init` executes a timed init step
- **THEN** the helper runs the step and logs its duration at debug level with the step's name

#### Scenario: Result-returning operation is supported
- **WHEN** a timed operation produces a value and is passed as a `Supplier` to the helper
- **THEN** the helper returns the value to the caller after logging the duration

### Requirement: Measure individual component registration
The Registry SHALL time the full creation and initialization of each non-lazy component by wrapping its `getOrCreate` call with the same helper, logging the duration at debug level using the component's class name.

#### Scenario: Component duration is logged
- **WHEN** a component is created and initialized during `Registry.init`
- **THEN** the helper logs the component's full creation/initialization duration at debug level with the component's class name

#### Scenario: Lazy and skipped components are not timed
- **WHEN** a component is lazy, fails a condition, or does not match the current gamemode
- **THEN** no timing log line is produced for that component

### Requirement: Timing output is debug-only
The timing helper SHALL emit its duration lines via `arc.util.Log.debug` so they only appear when debug logging is enabled.

#### Scenario: Omitted when debug logging is disabled
- **WHEN** debug logging is disabled in the game/arc configuration
- **THEN** no timing lines are printed
