# portable-registry

## Purpose

Defines the pure-Java dependency injection and lifecycle management engine residing in the `annotation` module, providing constructor injection, lifecycle hooks, condition evaluation, and pluggable annotation handler registration without Mindustry dependencies.

## Requirements

### Requirement: Pluggable annotation handler registration

`Registry` SHALL provide registration APIs allowing calling modules to register custom handlers for annotations on components, methods, and fields. Handlers SHALL be invoked during component initialization.

#### Scenario: Method annotation handler invoked
- **WHEN** a method annotation handler is registered for an annotation type `A`
- **AND** a component with a method annotated with `@A` is initialized
- **THEN** `Registry` executes the registered handler with the annotation instance, the target `Method`, and the component instance

#### Scenario: Method handler respects condition checks
- **WHEN** a method annotated with `@A` is also annotated with a `@ConditionOn` whose condition returns `false`
- **THEN** the registered handler for `@A` is not invoked for that method

#### Scenario: Field and class annotation handlers invoked
- **WHEN** a field or class annotation handler is registered for annotation type `F` or `C`
- **AND** a component bearing `@F` on a field or `@C` on its class is initialized
- **THEN** `Registry` dispatches to the corresponding registered handler

### Requirement: Built-in component lifecycle without game engine coupling

`Registry` SHALL natively manage component lifecycles:
- Instantiating singletons via constructor injection.
- Invoking zero-argument or injected methods annotated with `@Init` upon component creation.
- Detecting circular dependencies and throwing an informative exception.
- Invoking `@Destroy` methods in exact reverse order of component registration when `Registry.destroy()` is called.
- Skipping lazy components annotated with `@Lazy`.
- Evaluating `@ConditionOn` on component types and methods using `ConditionUtils`.

None of these core lifecycle capabilities SHALL depend on Mindustry or Arc.

#### Scenario: Native init and destroy lifecycle
- **WHEN** components with `@Init` and `@Destroy` annotations are initialized and then `Registry.destroy()` is invoked
- **THEN** `@Init` methods are called during creation
- **AND** `@Destroy` methods are called in reverse creation order during destruction

### Requirement: Pluggable logging and diagnostic output

`Registry` SHALL NOT import or directly invoke `arc.util.Log`. Diagnostic messages (component registration, condition skips, destroy errors) SHALL be dispatched through a configurable logger interface or consumer hooks.

#### Scenario: Custom logger receives registry events
- **WHEN** a logger delegate is configured on `Registry`
- **AND** components are registered or conditions fail
- **THEN** the configured logger delegate receives the diagnostic messages without throwing
