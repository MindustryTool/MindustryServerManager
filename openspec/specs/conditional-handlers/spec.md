# conditional-handlers

## Purpose

Allows `@ConditionOn` to gate individual method-level handler registrations so commands, listeners, triggers, schedules, and action filters can be enabled or disabled based on runtime conditions.

## Requirements

### Requirement: ConditionOn applicable to methods

The `@ConditionOn` annotation SHALL be applicable to both classes and methods (`@Target({TYPE, METHOD})`). A method annotated with `@ConditionOn` MUST declare a `Condition` implementation class whose no-argument constructor can be instantiated.

#### Scenario: Annotation placed on a method
- **WHEN** a method declares `@ConditionOn(SomeCondition.class)`
- **THEN** the source compiles without error
- **AND** the `@ConditionOn` annotation is visible at runtime with the declared condition class

#### Scenario: Existing class-level usage still compiles
- **WHEN** a component class declares `@ConditionOn(SomeCondition.class)` as before
- **THEN** the source compiles without error
- **AND** behavior is unchanged

### Requirement: Method-level condition gates handler registration

During component initialization, for every method annotated with one of `@ClientCommand`, `@Listener`, `@PlayerActionFilter`, `@ServerCommand`, `@Schedule`, or `@Trigger`, the system SHALL evaluate a `@ConditionOn` present on that same method. If the condition's `check()` returns `false`, the handler registration for that method SHALL be skipped entirely. If the method has no `@ConditionOn`, or the condition's `check()` returns `true`, the handler SHALL be registered as usual.

#### Scenario: Passing condition registers the handler
- **WHEN** a method annotated with `@Schedule` also declares `@ConditionOn(AlwaysTrue.class)` and `AlwaysTrue.check()` returns `true`
- **THEN** the schedule is registered and invoked per the `@Schedule` settings

#### Scenario: Failing condition skips the handler
- **WHEN** a method annotated with `@ClientCommand` also declares `@ConditionOn(AlwaysFalse.class)` and `AlwaysFalse.check()` returns `false`
- **THEN** the command is NOT registered
- **AND** executing the command name results in an "unknown command" response

#### Scenario: No condition on method
- **WHEN** a method annotated with `@Listener` has no `@ConditionOn`
- **THEN** the listener is registered and invoked on its event type as usual

#### Scenario: Gating applies to all six handler annotations
- **WHEN** methods annotated with `@Listener`, `@Trigger`, `@PlayerActionFilter`, `@ServerCommand`, `@ClientCommand`, and `@Schedule` each declare a failing `@ConditionOn`
- **THEN** none of those six handlers are registered

### Requirement: Condition evaluation semantics

Conditions SHALL be evaluated once at component initialization time. Each `Condition` implementation SHALL be instantiated via its public no-argument constructor, matching class-level behavior. If the condition class cannot be instantiated or `check()` throws, initialization SHALL fail with a `RuntimeException` naming the affected target.

#### Scenario: Condition instantiated via no-arg constructor
- **WHEN** a method-level `@ConditionOn` references a `Condition` class with a no-argument constructor
- **THEN** the constructor is invoked and the returned instance's `check()` result is used

#### Scenario: Broken condition fails loudly
- **WHEN** a method-level `@ConditionOn` references a `Condition` class that cannot be instantiated
- **THEN** component initialization throws a `RuntimeException` indicating the failure and the affected method

#### Scenario: Single evaluation at registration
- **WHEN** a gated method's condition returns `true` at initialization
- **THEN** the condition is not re-evaluated afterward
- **AND** the handler remains registered for the lifetime of the component regardless of later changes to the condition's state

### Requirement: Class-level condition behavior preserved

The existing class-level `@ConditionOn` check during registry scanning SHALL remain functionally unchanged. Components whose class-level condition returns `false` SHALL NOT be instantiated, exactly as before this change.

#### Scenario: Class-level failing condition still skips component
- **WHEN** a `@Component` class declares `@ConditionOn(AlwaysFalse.class)`
- **THEN** the component is not instantiated
- **AND** its methods are not registered

#### Scenario: Class-level passing condition still instantiates
- **WHEN** a `@Component` class declares `@ConditionOn(AlwaysTrue.class)`
- **THEN** the component is instantiated and its handlers are registered as usual

### Requirement: Init and FileWatcher unaffected

`@ConditionOn` on a method SHALL NOT suppress `@Init`, `@Destroy`, or `@FileWatcher` processing for that same method; the gate applies only to the six handler annotation types listed above.

#### Scenario: Init on a condition-gated method still runs
- **WHEN** a method annotated with `@Init` also declares a failing `@ConditionOn`
- **THEN** the `@Init` method is still invoked during component initialization