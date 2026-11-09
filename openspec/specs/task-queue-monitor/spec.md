# task-queue-monitor Specification

## Purpose
Periodic monitoring and origin breakdown logging for Mindustry's `Core.app` task queue when size exceeds threshold.

## Requirements

### Requirement: Queue Size Threshold Monitoring
The system SHALL monitor the pending task queue in `Core.app` and identify when the number of queued tasks exceeds a predefined threshold of 200 tasks.

#### Scenario: Queue size below threshold
- **WHEN** the background monitor checks the queue and pending tasks count is 200 or fewer
- **THEN** no warning logs SHALL be generated

#### Scenario: Queue size exceeds threshold
- **WHEN** the background monitor checks the queue and pending tasks count exceeds 200
- **THEN** the system SHALL emit a warning log reporting the total queue size and breakdown of top originating classes

### Requirement: Origin Histogram Breakdown
When the task queue exceeds the threshold, the system SHALL group pending tasks by originating class and log the top contributors.

#### Scenario: Tasks created as lambdas or method references
- **WHEN** tasks in the queue originate from lambdas (e.g. `Foo$$Lambda...`)
- **THEN** the system SHALL strip lambda and dynamic class suffixes and group them by the enclosing class name

#### Scenario: Breakdown sorting and limits
- **WHEN** tasks originate from multiple distinct classes during a spike
- **THEN** the system SHALL order originating classes by task count in descending order and log up to 5 top classes

### Requirement: Fail-Loud Reflection Initialization
The system SHALL fail loudly if Arc internals cannot be accessed via reflection at startup.

#### Scenario: Reflection fields missing or inaccessible
- **WHEN** the application instance lacks the expected `runnables` field or field type does not match
- **THEN** the system SHALL throw an `IllegalStateException` with clear diagnostic information
