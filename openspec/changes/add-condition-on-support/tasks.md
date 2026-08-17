## 1. Annotation change

- [x] 1.1 Widen `@Target` of `ConditionOn` in `plugin/src/main/java/plugin/annotations/ConditionOn.java` to `{ElementType.TYPE, ElementType.METHOD}`

## 2. Condition evaluation helper

- [x] 2.1 Create `ConditionUtils` helper (in `plugin/core`) with `passes(Method)` and `passes(Class)` methods that return `true` when no `@ConditionOn` is present and otherwise instantiate the condition via its no-arg constructor and return `check()`
- [x] 2.2 Make `ConditionUtils` throw a `RuntimeException` naming the target when the condition class cannot be instantiated or `check()` throws

## 3. Registry integration

- [x] 3.1 Replace the inline class-level `@ConditionOn` check in `Registry.init` with `ConditionUtils.passes(clazz)` (skip the component when it returns `false`), preserving existing behavior
- [x] 3.2 In `Registry.initialize`, guard the `@Schedule` dispatch with `ConditionUtils.passes(method)` so a failing condition skips scheduling
- [x] 3.3 Guard the `@Listener` and `@Trigger` dispatches with `ConditionUtils.passes(method)` so failing conditions skip event registration
- [x] 3.4 Guard the `@ClientCommand` and `@ServerCommand` dispatches with `ConditionUtils.passes(method)` so failing conditions skip command registration
- [x] 3.5 Guard the `@PlayerActionFilter` dispatch with `ConditionUtils.passes(method)` so a failing condition skips filter registration
- [x] 3.6 Confirm `@Init`, `@Destroy`, `@Configuration`, `@Persistence`, and `@FileWatcher` processing is NOT gated by the method-level condition

## 4. Verification

- [x] 4.1 Compile the plugin with `.\gradlew.bat :plugin:build --console=plain` and confirm it exits 0
- [x] 4.2 Confirm existing class-level `@ConditionOn` usages (`GriefDetectService`, `HubService`, `SecurityService`, `OfficialCommands`) still behave as before