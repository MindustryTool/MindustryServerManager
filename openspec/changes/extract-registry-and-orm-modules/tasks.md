## 1. Extract Database Subproject

- [x] 1.1 Create `database` directory structure and `database/build.gradle.kts` with SQLite JDBC and test dependencies
- [x] 1.2 Move ORM sources from `plugin/src/main/java/plugin/orm/` to `database/src/main/java/plugin/orm/`
- [x] 1.3 Move ORM tests from `plugin/src/test/java/plugin/orm/` to `database/src/test/java/plugin/orm/`
- [x] 1.4 Update `settings.gradle.kts` to include `:database`
- [x] 1.5 Update `plugin/build.gradle.kts` to depend on `:database` and bundle `:database` classes in `tasks.jar`
- [x] 1.6 Verify `:database:test` compiles and passes all unit tests

## 2. Decouple Annotation Module from Mindustry

- [x] 2.1 Relocate `@Trigger` annotation from `annotation/src/main/java/plugin/annotations/Trigger.java` to `plugin/src/main/java/plugin/annotations/Trigger.java`
- [x] 2.2 Remove `compileOnly("Anuken:Mindustry:${property("mindustryVersion")}")` from `annotation/build.gradle.kts`
- [x] 2.3 Verify `annotation` module compiles with zero Mindustry or Arc dependencies

## 3. Extract Registry and Condition Utilities into Annotation Module

- [x] 3.1 Move `ConditionUtils.java` from `plugin/src/main/java/plugin/core/` to `annotation/src/main/java/plugin/core/`
- [x] 3.2 Implement pluggable annotation handler registration SPI (`registerMethodHandler`, `registerFieldHandler`, `registerClassHandler`) and logging delegate in `Registry.java`
- [x] 3.3 Move `Registry.java` to `annotation/src/main/java/plugin/core/` removing direct dependencies on `arc.util.Log`, `TimeUtils`, and plugin managers
- [x] 3.4 Move `RegistryDestroyOrderTest` to `annotation/src/test/java/plugin/core/` and add tests for handler registration and condition filtering
- [x] 3.5 Verify `:annotation:test` compiles and passes

## 4. Integrate Registry Handlers in Plugin Bootstrap

- [x] 4.1 Implement `PluginBootstrap` in `plugin` to register annotation handlers (`@Configuration`, `@Persistence`, `@Schedule`, `@Listener`, `@Trigger`, `@FileWatcher`, `@ClientCommand`, `@ServerCommand`, `@PlayerActionFilter`) and route `Registry` log events to `arc.util.Log`
- [x] 4.2 Update `Control.init()` to invoke handler registration prior to `Registry.init()`
- [x] 4.3 Verify `:plugin:test` and full project build (`./gradlew test jar`) succeed and `plugin.jar` packages expected classes
