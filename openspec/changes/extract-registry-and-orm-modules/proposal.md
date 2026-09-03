## Why

Currently, core architectural infrastructure is tightly coupled across the codebase:
1. The `annotation` module still declares a compile-time dependency on `Anuken:Mindustry` due to `@Trigger`, even though annotation and code-generation libraries should be lightweight, reusable, and decoupled from game engine internals.
2. `Registry` (the dependency injection and lifecycle engine) and `ConditionUtils` live in the `plugin` module and are hard-coupled to Mindustry-specific loggers, event registrars, command handlers, and filter managers, preventing their reuse outside the game plugin.
3. The SQLite ORM subsystem lives directly inside `plugin.orm`, even though it is a pure JDBC/SQLite abstraction without any Mindustry or Arc dependencies.

Extracting `Registry` into the `annotation` module (with zero Mindustry dependencies) and extracting the SQLite ORM into a dedicated `:database` subproject establishes clean architectural boundaries, eliminates unnecessary compile dependencies, and makes core infrastructure independently testable and reusable.

## What Changes

- **Extract SQLite ORM into `:database` subproject**: Move `plugin/src/main/java/plugin/orm` and its tests into a new root Gradle subproject `:database`.
- **Update root build configuration**: Include `database` in `settings.gradle.kts`, configure `:database` in root `build.gradle.kts`, and add `:database` dependency and jar bundling to `plugin/build.gradle.kts`.
- **Remove Mindustry dependency from `annotation`**: Remove `compileOnly("Anuken:Mindustry:${property("mindustryVersion")}")` from `annotation/build.gradle.kts`.
- **Relocate `@Trigger` annotation**: Move `@Trigger` from `annotation` to `plugin` module (`plugin.annotations.Trigger`) so `annotation` contains zero Mindustry types.
- **Extract `Registry` and `ConditionUtils` to `annotation`**: Move `Registry` and `ConditionUtils` into `annotation/src/main/java/plugin/core/` under pure Java 17, with no Mindustry or Arc imports.
- **Pluggable annotation handler SPI in `Registry`**: Decouple `Registry` from plugin-specific managers (`EventRegistrar`, `CommandHandler`, `ActionFilterManager`, `ConfigManager`, `PersistenceManager`, `Scheduler`, `FileWatcherManager`) by introducing a registration API (e.g. `registerMethodHandler`, `registerFieldHandler`, `registerClassHandler`) configured during plugin bootstrap.
- **Pluggable logging & timing**: Replace direct `arc.util.Log` references in `Registry` with standard logging or configurable log/timing listeners bridged by `plugin`.

## Capabilities

### New Capabilities
- `database-module`: Dedicated `:database` subproject housing the SQLite ORM (query builders, table/column schema, converters, transactions, and unit tests), consumed by `plugin` and bundled into `plugin.jar`.
- `portable-registry`: Pure-Java DI and lifecycle container in `annotation` module providing constructor injection, `@Init`, `@Destroy`, `@ConditionOn` evaluation, and pluggable annotation handler registration without Mindustry dependencies.

### Modified Capabilities
- `annotation-module`: Remove `compileOnly("Anuken:Mindustry")` dependency, relocate `@Trigger` to `plugin`, and add `Registry` and `ConditionUtils` to the `annotation` subproject.

## Impact

- **Build scripts**: `settings.gradle.kts`, `build.gradle.kts`, `annotation/build.gradle.kts`, `plugin/build.gradle.kts`.
- **Dependencies**: `plugin` gains `implementation(project(":database"))`. `annotation` drops `Anuken:Mindustry`.
- **Code locations**:
  - `plugin/src/main/java/plugin/orm/**` -> `database/src/main/java/plugin/orm/**`
  - `plugin/src/test/java/plugin/orm/**` -> `database/src/test/java/plugin/orm/**`
  - `plugin/src/main/java/plugin/core/Registry.java` -> `annotation/src/main/java/plugin/core/Registry.java`
  - `plugin/src/main/java/plugin/core/ConditionUtils.java` -> `annotation/src/main/java/plugin/core/ConditionUtils.java`
  - `annotation/src/main/java/plugin/annotations/Trigger.java` -> `plugin/src/main/java/plugin/annotations/Trigger.java`
- **Plugin bootstrap**: `plugin.Control` or bootstrap sequence will register annotation handlers with `Registry` for Mindustry-specific annotations (`@Listener`, `@Trigger`, `@ClientCommand`, `@ServerCommand`, `@PlayerActionFilter`, `@Schedule`, `@Configuration`, `@Persistence`, `@FileWatcher`).
