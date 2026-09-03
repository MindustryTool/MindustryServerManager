## Context

The repository consists of four subprojects:
- `:annotation`: Declares component and feature annotations (`@Component`, `@Listener`, etc.) and contains `ComponentRegistryProcessor`. It currently depends on `compileOnly("Anuken:Mindustry")` because of `@Trigger`.
- `:plugin`: The Mindustry server plugin (`plugin.Control`), containing game features, gamemodes, commands, database integration, `Registry`, and the SQLite ORM (`plugin.orm`).
- `:server`: Standalone manager service.
- `:dto`: Shared network and data transfer objects.

Currently, `Registry` (the dependency injection and lifecycle engine) is located in `plugin/src/main/java/plugin/core/Registry.java`. It is tightly coupled to Mindustry/Arc APIs (`arc.util.Log`, `TimeUtils`) and hardcodes handlers for plugin-specific annotations (`@Listener`, `@Trigger`, `@ClientCommand`, `@ServerCommand`, `@PlayerActionFilter`, `@Schedule`, `@Configuration`, `@Persistence`, `@FileWatcher`).
Additionally, the SQLite ORM engine in `plugin/src/main/java/plugin/orm` has zero game engine dependencies, yet lives inside `plugin`.

## Goals / Non-Goals

**Goals:**
- Make `:annotation` completely independent of Mindustry: remove `compileOnly("Anuken:Mindustry")` and all Mindustry/Arc imports from `:annotation`.
- Move `@Trigger` to `plugin` module (`plugin.annotations.Trigger`) preserving its signature `EventType.Trigger value()`.
- Move `Registry` and `ConditionUtils` into `:annotation` (`annotation/src/main/java/plugin/core/`) under pure Java 17.
- Introduce a flexible, pluggable annotation handler registration mechanism on `Registry` so that `plugin` can register handlers for plugin/Mindustry-specific annotations.
- Abstract logging and timing in `Registry` so it can run standalone without `arc.util.Log`.
- Extract `plugin.orm` and its tests into a new root subproject `:database` with `org.xerial:sqlite-jdbc`.
- Configure `plugin` to depend on `:database` and bundle `:database` into the final `plugin.jar`.

**Non-Goals:**
- Changing existing public ORM APIs or query syntax.
- Altering the behavior of `ComponentRegistryProcessor` (it continues generating `plugin.core.ComponentRegistry`).
- Changing Mindustry command, listener, or filter execution semantics.

## Decisions

### 1. Pluggable Annotation Handler SPI in `Registry`
`Registry` will handle core lifecycle concerns natively:
- `@Init`: invokes annotated zero-argument or injected initialization method.
- `@Destroy`: invokes annotated cleanup method during `Registry.destroy()`.
- `@ConditionOn`: evaluates conditions via `ConditionUtils`.
- `@Lazy`: defers eager instantiation during startup scan.

For domain-specific annotations, `Registry` will expose programmatic handler hooks:
```java
public interface MethodAnnotationHandler<T extends Annotation> {
    void handle(T annotation, Method method, Object instance);
}
public interface FieldAnnotationHandler<T extends Annotation> {
    void handle(T annotation, Field field, Object instance);
}
public interface ClassAnnotationHandler<T extends Annotation> {
    void handle(T annotation, Object instance);
}
```
`Registry` will provide registration methods:
- `registerMethodHandler(Class<A> annotationClass, MethodAnnotationHandler<A> handler)`
- `registerFieldHandler(Class<A> annotationClass, FieldAnnotationHandler<A> handler)`
- `registerClassHandler(Class<A> annotationClass, ClassAnnotationHandler<A> handler)`

During component initialization, `Registry` evaluates `ConditionUtils.passes(method)` before dispatching to registered method handlers, preserving current condition evaluation semantics.
In `plugin`, a bootstrap initializer (e.g., `PluginBootstrap.registerHandlers()`) registers the handlers for `@Configuration`, `@Persistence`, `@Schedule`, `@Listener`, `@Trigger`, `@FileWatcher`, `@ClientCommand`, `@ServerCommand`, and `@PlayerActionFilter` before `Registry.init()` runs.

*Alternatives considered:*
- Java `ServiceLoader`: Adds discovery indirection and classloader fragility inside the Mindustry plugin runtime.
- Hardcoding handlers via reflection by class name: Fragile and obscures dependencies. Explicit registration in bootstrap code is clear, fast, and type-safe.

### 2. Logging and Timing Abstraction
In `Registry`:
- Replace direct `arc.util.Log` calls with a configurable `RegistryLogger` interface (or `Consumer<String>` debug/info and `BiConsumer<String, Throwable>` error callbacks), defaulting to standard `System.Logger` or no-op.
- `plugin` configures the logger at startup to route log messages to `arc.util.Log.debug`, `arc.util.Log.info`, and `arc.util.Log.err`.
- Replace `TimeUtils.measure` calls in `Registry` with standard `System.nanoTime()` duration checks or configurable timing hooks.

*Alternatives considered:*
- Bringing SLF4J into `:annotation`: Adds unnecessary third-party dependencies to what should be a lean annotation module. A simple delegate interface keeps `:annotation` dependency-free.

### 3. Relocate `@Trigger` to `:plugin`
`@Trigger` takes an argument of type `mindustry.game.EventType.Trigger`. Because the requirement specifies that `:annotation` must not contain any Mindustry code and must remove `compileOnly("Anuken:Mindustry")`, `@Trigger` cannot reside in `:annotation`.
`@Trigger` will be moved to `plugin/src/main/java/plugin/annotations/Trigger.java`. Since its package name remains `plugin.annotations`, existing references across game modes (`CataliGamemode`, `FloodGamemode`, `ZigerGamemode`, `EventRegistrar`) require no import changes.

*Alternatives considered:*
- Changing `@Trigger` to use string trigger names: Loses compile-time type safety against Mindustry's `EventType.Trigger` enum and breaks existing callers. Moving the file to `:plugin` retains full type safety.

### 4. Separate `:database` Subproject
- Create root directory `database/` with standard Gradle layout (`database/src/main/java` and `database/src/test/java`).
- Register `:database` in `settings.gradle.kts`.
- In root `build.gradle.kts`, configure `:database` subproject with standard Java 17 and repositories.
- `database/build.gradle.kts`:
  ```kotlin
  dependencies {
      implementation("org.xerial:sqlite-jdbc:3.43.2.0")
      testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
      testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  }
  tasks.test { useJUnitPlatform() }
  ```
- In `plugin/build.gradle.kts`:
  - Add `implementation(project(":database"))`.
  - In `tasks.jar`: add `:database:classes` dependency and `from(project(":database").sourceSets.main.get().output)`.

## Risks / Trade-offs

- **[Risk] Handler registration order**: If `Registry.init()` is called before `plugin` registers its annotation handlers, components will not have their listeners, commands, or tasks registered.
  → **Mitigation**: Encapsulate all plugin handler registrations in `PluginBootstrap.registerHandlers()` and ensure `Control.init()` calls it as the very first step before `Registry.init()`. Add unit tests asserting handler dispatch.

- **[Risk] Missing database ORM classes in packaged `plugin.jar`**: If the jar task does not properly bundle `:database` outputs, runtime `ClassNotFoundException` or `NoClassDefFoundError` will occur when the server starts.
  → **Mitigation**: Explicitly include `project(":database").sourceSets.main.get().output` in `plugin/build.gradle.kts` `tasks.jar` and verify jar contents during build verification.
