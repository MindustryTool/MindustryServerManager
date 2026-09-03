# database-module

## Purpose

Defines the dedicated `:database` Gradle subproject housing the SQLite ORM engine, query builders, schema models, converters, transactions, and unit tests without game engine coupling.

## Requirements

### Requirement: Dedicated database subproject

The repository SHALL provide a dedicated Gradle subproject named `:database` in the root `database` directory. The subproject SHALL house all SQLite ORM implementation classes and unit tests currently under `plugin.orm`.

#### Scenario: Database files exist under database directory
- **WHEN** the repository layout is inspected
- **THEN** SQLite ORM source files are located in `database/src/main/java/plugin/orm/`
- **AND** SQLite ORM unit tests are located in `database/src/test/java/plugin/orm/`
- **AND** no `plugin/src/main/java/plugin/orm/` directory exists

### Requirement: Standalone database dependencies

The `:database` subproject SHALL depend solely on standard Java 17 and `org.xerial:sqlite-jdbc:3.43.2.0` (plus JUnit 5 test dependencies). It SHALL NOT depend on `:plugin`, `:server`, `:dto`, `:annotation`, Mindustry, or Arc.

#### Scenario: Isolated compilation
- **WHEN** `:database:build` or `:database:test` is executed
- **THEN** the subproject compiles and passes all unit tests independently of the game engine or plugin

### Requirement: Plugin consumption and packaging

The `plugin` module SHALL declare an `implementation` dependency on `:database`. The `tasks.jar` packaging task in `plugin/build.gradle.kts` SHALL bundle the compiled classes of `:database` into the final `plugin.jar`.

#### Scenario: Bundled plugin jar contains database classes
- **WHEN** `plugin.jar` is built
- **THEN** it contains all `plugin/orm/**/*.class` files produced by the `:database` subproject
