# conditional-handlers

## Purpose

Allows `@ConditionOn` to gate individual method-level handler registrations so commands, listeners, triggers, schedules, and action filters can be enabled or disabled based on runtime conditions.

## Requirements

### Requirement: ConditionOn applicable to methods

The `@ConditionOn` annotation SHALL be applicable to both classes and methods (`@Target({TYPE, METHOD})`) and SHALL be repeatable. A method annotated with `@ConditionOn` MUST declare a `Condition` implementation class that can be instantiated via its no-argument constructor, or via a `String[]` constructor when `args` are provided.

#### Scenario: Annotation placed on a method
- **WHEN** a method declares `@ConditionOn(SomeCondition.class)`
- **THEN** the source compiles without error
- **AND** the `@ConditionOn` annotation is visible at runtime with the declared condition class

#### Scenario: Existing class-level usage still compiles
- **WHEN** a component class declares `@ConditionOn(SomeCondition.class)` as before
- **THEN** the source compiles without error
- **AND** behavior is unchanged

### Requirement: ConditionOn supports constructor arguments

`@ConditionOn` SHALL accept simple string arguments via `args()` alongside the condition class in `value()`. When `args()` is non-empty, the condition SHALL be instantiated via a public `String[]` constructor receiving those args; when empty, the existing no-argument constructor SHALL be used. If `args()` is non-empty and the condition class has no `String[]` constructor, evaluation SHALL fail with a `RuntimeException` naming the affected target. `args` SHALL be passed positionally in declaration order.

#### Scenario: Args are passed to the condition
- **WHEN** a target declares `@ConditionOn(value = GamemodeCondition.class, args = {"catali"})` and evaluation runs
- **THEN** the condition is instantiated with `new GamemodeCondition(new String[]{"catali"})` and its `check()` result gates the target

#### Scenario: No args uses the no-arg constructor
- **WHEN** a target declares `@ConditionOn(Cfg.OnHub.class)` with no args
- **THEN** the condition is instantiated via its no-argument constructor, exactly as before

#### Scenario: Args without a String[] constructor fails loudly
- **WHEN** a target declares args for a condition class lacking a `String[]` constructor
- **THEN** evaluation throws a `RuntimeException` naming the affected target

### Requirement: Multiple ConditionOn annotations are ANDed

`@ConditionOn` SHALL be repeatable on a single target via its container annotation. When a target declares multiple `@ConditionOn` annotations, ALL of them SHALL pass for the target to be enabled. A target with no `@ConditionOn` SHALL always be enabled. Evaluation SHALL happen once per annotation at the existing timing (initialization/registration).

#### Scenario: No condition is always enabled
- **WHEN** a class or method has no `@ConditionOn`
- **THEN** it passes unconditionally

#### Scenario: All conditions must pass
- **WHEN** a class declares two `@ConditionOn` annotations and the current state satisfies both
- **THEN** the class passes and is enabled

#### Scenario: One failing condition disables the target
- **WHEN** a class declares two `@ConditionOn` annotations and exactly one fails
- **THEN** the class fails and is not enabled

#### Scenario: Repeatable form compiles at class and method level
- **WHEN** repeated `@ConditionOn` annotations are placed on both a class and a method
- **THEN** the source compiles without error and both targets are evaluated independently

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

Conditions SHALL be evaluated once at component initialization time. Each `Condition` implementation SHALL be instantiated via its public no-argument constructor, or via a public `String[]` constructor receiving `args` when `args()` is non-empty, matching class-level behavior. If the condition class cannot be instantiated or `check()` throws, initialization SHALL fail with a `RuntimeException` naming the affected target.

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