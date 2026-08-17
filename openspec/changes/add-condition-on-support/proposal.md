## Why

`@ConditionOn` currently only gates whole component classes during registry scanning. Method-level handlers (`@ClientCommand`, `@Listener`, `@PlayerActionFilter`, `@ServerCommand`, `@Schedule`, `@Trigger`) cannot be conditionally registered based on runtime configuration (e.g. `Cfg.OnOfficial`, `Cfg.OnHub`). Developers must either split handlers into separate components or guard logic inside method bodies, which is awkward and error-prone.

## What Changes

- Extend the `@ConditionOn` annotation so it can also be placed on methods, in addition to classes (`@Target` becomes `{TYPE, METHOD}`).
- When a method annotated with any of `@ClientCommand`, `@Listener`, `@PlayerActionFilter`, `@ServerCommand`, `@Schedule`, or `@Trigger` also carries `@ConditionOn`, evaluate the condition during component initialization and skip that registration entirely when the condition returns `false`.
- Add a shared condition-evaluation helper and reuse it for the existing class-level check so behavior stays consistent.
- Class-level `@ConditionOn` semantics remain unchanged (backwards compatible).

## Capabilities

### New Capabilities
- `conditional-handlers`: Allows `@ConditionOn` on methods so individual handler registrations (commands, listeners, triggers, schedules, action filters) can be enabled or disabled based on runtime conditions.

### Modified Capabilities
<!-- No existing spec-level requirements change. -->

## Impact

- `plugin/src/main/java/plugin/annotations/ConditionOn.java` - widen `@Target` to include `ElementType.METHOD`.
- `plugin/src/main/java/plugin/core/Registry.java` - evaluate method-level conditions before dispatching handler registrations in `initialize`; refactor the class-level check to use the shared helper.
- New helper class (e.g. `ConditionUtils`) for condition evaluation, placed in `plugin.annotations` or `plugin.core`.
- No impact on existing components; existing class-level `@ConditionOn` usages (e.g. `GriefDetectService`, `HubService`, `SecurityService`, `OfficialCommands`) keep working unchanged.
