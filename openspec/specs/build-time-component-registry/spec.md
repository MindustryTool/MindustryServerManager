# build-time-component-registry

## Requirements

### Requirement: Build-time component registry generation
The build SHALL generate a `ComponentRegistry` class containing the list of classes annotated with `@Component` in the plugin source, without performing a classpath scan at runtime.

#### Scenario: Generated registry lists all components
- **WHEN** the plugin module is built
- **THEN** the generated `ComponentRegistry` class contains every `@Component`-annotated class compiled into the plugin

#### Scenario: Registry is regenerated on each build
- **WHEN** a build runs and a `@Component` class is added or removed
- **THEN** the generated `ComponentRegistry` reflects the current component set (no stale entries)

### Requirement: Registry init uses the generated registry
`Registry.init` SHALL obtain the component class list from the generated `ComponentRegistry` instead of using a runtime `Reflections` scan.

#### Scenario: No runtime reflection scan
- **WHEN** `Registry.init` is invoked
- **THEN** it reads the component classes from `ComponentRegistry` and does not construct a `Reflections` scanner

#### Scenario: Runtime filtering is preserved
- **WHEN** the component list is read from `ComponentRegistry`
- **THEN** annotation/interface, lazy, and condition (`ConditionOn`) filtering still run at runtime in `Registry.init`, and gamemode gating is performed by the `ConditionOn` mode check rather than a separate `@Gamemode` filter
