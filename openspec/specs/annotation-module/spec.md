# annotation-module

## Purpose

Defines the dedicated `annotation` Gradle subproject that owns the generic annotation declarations, `Registry`, `ConditionUtils`, and the annotation processor, with zero Mindustry or Arc dependencies.

## Requirements

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

### Requirement: Registry and condition utilities in annotation module

The `Registry` dependency injection container and `ConditionUtils` condition evaluator SHALL live in the `annotation` module under package `plugin.core`. They SHALL compile using only JDK APIs and annotations in the `annotation` module, with no references to Mindustry or Arc.

#### Scenario: Registry compiles in annotation module
- **WHEN** the `annotation` module is compiled
- **THEN** `plugin.core.Registry` and `plugin.core.ConditionUtils` compile successfully without Mindustry on the classpath

### Requirement: Plugin consumes annotations at runtime

The `plugin` module SHALL declare an `implementation` dependency on the `annotation` module so the annotation classes remain on the plugin runtime classpath (they are read reflectively by `Registry`). The annotations SHALL be bundled into the built `plugin.jar`.

#### Scenario: Annotations bundled into plugin jar
- **WHEN** `plugin.jar` is built
- **THEN** it contains the `plugin.annotations.*` classes

### Requirement: Processor co-located with annotations

The `ComponentRegistryProcessor` SHALL live in the `annotation` module together with the annotation declarations, so it references `plugin.annotations.Component` by direct class reference.

#### Scenario: Processor references the annotation class directly
- **WHEN** the processor source is inspected
- **THEN** it imports `plugin.annotations.Component` and uses the class reference (e.g., in `getElementsAnnotatedWith`) rather than comparing qualified-name strings