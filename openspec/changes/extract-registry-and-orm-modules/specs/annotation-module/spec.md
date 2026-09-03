## MODIFIED Requirements

### Requirement: Annotations isolated in the annotation module

All generic plugin annotation declarations (`plugin.annotations.Component`, `ConditionOn`, `Condition`, `ClientCommand`, `ServerCommand`, `Configuration`, `Param`, `Init`, `Destroy`, `Lazy`, `Listener`, `Schedule`, `FileWatcher`, `MainThread`, `Persistence`, `PlayerActionFilter`) SHALL live in the dedicated `annotation` Gradle subproject with their fully-qualified names unchanged. The Mindustry-specific `@Trigger` annotation SHALL live in the `plugin` module (`plugin/src/main/java/plugin/annotations/Trigger.java`) to ensure the `annotation` module contains zero Mindustry code.

#### Scenario: Generic annotation sources live in the annotation module
- **WHEN** the repository layout is inspected
- **THEN** the generic annotation declarations are under `annotation/src/main/java/plugin/annotations/`
- **AND** `@Trigger` is located under `plugin/src/main/java/plugin/annotations/`

#### Scenario: Fully-qualified names unchanged
- **WHEN** the plugin module is compiled
- **THEN** it references all annotations (including `@Trigger`) by their existing FQCNs (e.g., `plugin.annotations.Component`, `plugin.annotations.Trigger`)

### Requirement: Annotation module dependencies

The `annotation` module SHALL depend only on JDK APIs. It SHALL NOT declare any dependency on `Anuken:Mindustry` or Arc, and SHALL NOT depend on the `plugin` module or any other project module.

#### Scenario: Pure JDK dependencies
- **WHEN** the annotation module's declared dependencies are inspected
- **THEN** they consist solely of the JDK and test dependencies (such as compile-testing and junit)
- **AND** there is no dependency on `Anuken:Mindustry`

## ADDED Requirements

### Requirement: Registry and condition utilities in annotation module

The `Registry` dependency injection container and `ConditionUtils` condition evaluator SHALL live in the `annotation` module under package `plugin.core`. They SHALL compile using only JDK APIs and annotations in the `annotation` module, with no references to Mindustry or Arc.

#### Scenario: Registry compiles in annotation module
- **WHEN** the `annotation` module is compiled
- **THEN** `plugin.core.Registry` and `plugin.core.ConditionUtils` compile successfully without Mindustry on the classpath
