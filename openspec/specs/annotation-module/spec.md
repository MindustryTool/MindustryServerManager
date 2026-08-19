# annotation-module

## Purpose

Defines the dedicated `annotation` Gradle subproject that owns the plugin's annotation declarations and the annotation processor, and how the plugin consumes them at runtime and at compile time.

## Requirements

### Requirement: Annotations isolated in the annotation module

All plugin annotation declarations (`plugin.annotations.Component`, `ConditionOn`, `Condition`, `ClientCommand`, `ServerCommand`, `Configuration`, `Param`, `Init`, `Destroy`, `Lazy`, `Listener`, `Trigger`, `Schedule`, `FileWatcher`, `MainThread`, `Persistence`, `PlayerActionFilter`) SHALL live in a dedicated `annotation` Gradle subproject with their fully-qualified names unchanged. The `plugin` module SHALL NOT contain these declarations.

#### Scenario: Annotation sources live in the annotation module
- **WHEN** the repository layout is inspected
- **THEN** the annotation declarations are under `annotation/src/main/java/plugin/annotations/`
- **AND** no annotation declarations remain under `plugin/src/main/java/plugin/annotations/`

#### Scenario: Fully-qualified names unchanged
- **WHEN** the plugin module is compiled
- **THEN** it references the annotations by their existing FQCNs (e.g., `plugin.annotations.Component`)

### Requirement: Annotation module dependencies

The `annotation` module SHALL depend only on JDK APIs plus `compileOnly("Anuken:Mindustry")` (required by `@Trigger`'s `EventType` attribute). It SHALL NOT depend on the `plugin` module or any other project module.

#### Scenario: Minimal module dependencies
- **WHEN** the annotation module's declared dependencies are inspected
- **THEN** they consist only of the JDK and the compile-only Mindustry dependency

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