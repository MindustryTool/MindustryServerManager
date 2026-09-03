# graph-removal Specification

## Purpose

Defines the complete decommissioning and removal of the visual flow graph subsystem across the project, including the graph subproject, build configurations, plugin services, annotation declarations and processor, and server REST/SSE endpoints.
## Requirements
### Requirement: Removal of the graph subproject and build wiring

The build system SHALL NOT include the `graph` subproject. The `settings.gradle.kts` file SHALL list only `annotation`, `server`, `plugin`, and `dto`. The `plugin` module build configuration SHALL NOT declare a dependency on `:graph` nor bundle `:graph` classes or artifacts into the plugin jar.

#### Scenario: Settings does not include graph
- **WHEN** Gradle configuration is evaluated
- **THEN** `:graph` is not present in the list of subprojects

#### Scenario: Plugin build does not depend on or package graph
- **WHEN** the `plugin` module `jar` task runs
- **THEN** it does not depend on `:graph:classes` and does not include `graph` classes or jars in its output

### Requirement: Removal of graph plugin code and tests

The `plugin` module SHALL NOT contain graph execution, scheduling, repository, or discovery services. The `plugin.graph` package and all its subpackages (`plugin.graph.schedule`, `plugin.graph.services`) and test classes SHALL be removed.

#### Scenario: Graph package absent in plugin
- **WHEN** the `plugin` source directories are inspected
- **THEN** no classes exist under `plugin/src/main/java/plugin/graph` or `plugin/src/test/java/plugin/graph`

#### Scenario: Plugin builds and passes tests
- **WHEN** `./gradlew :plugin:test` is executed
- **THEN** the compilation succeeds without referencing graph packages and all tests pass

### Requirement: Removal of graph annotations and processor

The `annotation` module SHALL NOT declare graph annotations or the graph index processor. The annotations `GraphCategory`, `GraphConstructor`, `GraphEvent`, `GraphFunction`, and `GraphProperty` SHALL be removed. The processor `GraphIndexProcessor` and its tests SHALL be removed, and the `javax.annotation.processing.Processor` SPI configuration SHALL NOT reference `GraphIndexProcessor`.

#### Scenario: Graph annotations removed
- **WHEN** the `annotation` module is compiled
- **THEN** no graph annotations exist in `plugin.annotations`

#### Scenario: Only ComponentRegistryProcessor registered
- **WHEN** `META-INF/services/javax.annotation.processing.Processor` is inspected
- **THEN** it contains only `plugin.processor.ComponentRegistryProcessor`

#### Scenario: Annotation tests pass
- **WHEN** `./gradlew :annotation:test` is executed
- **THEN** all tests pass successfully without graph index processor tests

### Requirement: Removal of graph server routes and gateway passthrough

The `server` module SHALL NOT expose graph REST routes or SSE streams. `GraphRoutes`, `GraphSse`, and `GraphSseBroker` SHALL be removed. `ServerMain` SHALL NOT register graph routes or SSE handlers. `GatewayService` SHALL NOT declare `graphRequest` methods or filter `graph.*` events.

#### Scenario: Graph routes and SSE unmapped
- **WHEN** the server starts
- **THEN** `/api/v2/graphs/**`, `/api/v2/graph/**`, and `/api/v2/graphs/{id}/debug/stream` endpoints are not registered

#### Scenario: GatewayService free of graph routing
- **WHEN** gateway events and RPC calls are processed
- **THEN** no special routing for `graph.*` events or `graphRequest` passthrough exists

#### Scenario: Server builds and tests pass
- **WHEN** `./gradlew :server:test` is executed
- **THEN** compilation succeeds and tests pass

