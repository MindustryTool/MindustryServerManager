# gamemode-gating

## Purpose

Defines how gamemode-specific components are enabled and disabled through the generic `@ConditionOn` system: a `Gamemode` component owns the persistent mode state, `GamemodeCondition` checks it, and the registry applies it uniformly — replacing the removed `@Gamemode` annotation.

## Requirements

### Requirement: Gamemode component

The plugin SHALL provide a `Gamemode` `@Component` that owns the gamemode state. It SHALL define the persistent settings key `plugin-gamemode`, expose `current()` returning the configured gamemode string (empty when unset), `active(String... modes)` returning whether the current gamemode case-insensitively matches any of the given modes, and `set(String)` that writes and force-saves the value. Reading the current gamemode SHALL NOT depend on component initialization order.

#### Scenario: Current gamemode from settings
- **WHEN** `Core.settings` contains `plugin-gamemode = "catali"` and `Gamemode.current()` is called
- **THEN** it returns `"catali"`

#### Scenario: Active checks case-insensitively
- **WHEN** the current gamemode is `"Ziger"` and `Gamemode.active("ziger")` is called
- **THEN** it returns `true`

#### Scenario: Mode matching any of several
- **WHEN** the current gamemode is `"ziger"` and `Gamemode.active("attack", "ziger")` is called
- **THEN** it returns `true`

#### Scenario: Set persists the mode
- **WHEN** `Gamemode.set("flood")` is called
- **THEN** `Core.settings` is updated and force-saved so a restart keeps the mode

### Requirement: GamemodeCondition checks the current gamemode

The plugin SHALL provide `GamemodeCondition implements Condition` whose `check()` returns whether the current gamemode case-insensitively matches any of the mode names passed to it. The modes SHALL be supplied via the condition's `String[]` constructor (e.g., `new GamemodeCondition(new String[]{"catali", "pvp"})`). With empty args, `check()` SHALL return `false`.

#### Scenario: Matching mode passes
- **WHEN** `GamemodeCondition` is constructed with modes `{"catali", "pvp"}` and the current gamemode is `"pvp"`
- **THEN** `check()` returns `true`

#### Scenario: Non-matching mode fails
- **WHEN** `GamemodeCondition` is constructed with modes `{"catali"}` and the current gamemode is `"flood"`
- **THEN** `check()` returns `false`

#### Scenario: Empty args never pass
- **WHEN** `GamemodeCondition` is constructed with no modes
- **THEN** `check()` returns `false` regardless of the current gamemode

### Requirement: Gamemode gating through ConditionOn

Gamemode-specific components SHALL declare their gamemode membership via `@ConditionOn(value = GamemodeCondition.class, args = ...)`, replacing the removed `@Gamemode` annotation. A target with `@ConditionOn(value = GamemodeCondition.class, args = {"survival", "TowerDefense"})` SHALL be active only when the current gamemode equals one of those modes (case-insensitive). The form SHALL behave identically at class and method level.

#### Scenario: Single-mode component is gated
- **WHEN** a component declares `@ConditionOn(value = GamemodeCondition.class, args = {"catali"})` and the current gamemode is `"catali"`
- **THEN** the component is instantiated and its handlers are registered

#### Scenario: Mismatched single mode is skipped
- **WHEN** a component declares `@ConditionOn(value = GamemodeCondition.class, args = {"catali"})` and the current gamemode is `"flood"`
- **THEN** the component is not instantiated and its handlers are not registered

#### Scenario: Multi-mode args match any declared mode
- **WHEN** a component declares `@ConditionOn(value = GamemodeCondition.class, args = {"attack", "ziger"})` and the current gamemode is `"attack"`
- **THEN** the component is instantiated

#### Scenario: On-demand creation in a mismatched mode fails loudly
- **WHEN** a gamemode component with a failing `@ConditionOn` is requested via `Registry.get(...)` or constructed on demand
- **THEN** creation fails with a `RuntimeException` naming the target

### Requirement: Gamemode annotation removed

The `@Gamemode` annotation SHALL be deleted from `plugin/annotations`. No production source SHALL reference `plugin.annotations.Gamemode` or the `@Gamemode` marker. The build-time registry generator SHALL no longer produce an entry for the annotation type.

#### Scenario: No Gamemode annotation remains
- **WHEN** the plugin source tree is inspected
- **THEN** no file references `@Gamemode` or imports `plugin.annotations.Gamemode`

#### Scenario: Component list has no annotation types
- **WHEN** `ComponentRegistry.COMPONENTS` is built
- **THEN** it contains no entry for `plugin.annotations.Gamemode`

### Requirement: Registry has no custom gamemode logic

`Registry` SHALL contain no gamemode-specific fields, settings key, filter block, or creation guard. Class-level gating during `Registry.init` and on-demand gating during `Registry.create` SHALL both be handled exclusively by `ConditionUtils.passes(...)`, which evaluates the repeatable `@ConditionOn` annotations. The admin `/gamemode` command SHALL write the mode through the `Gamemode` component.

#### Scenario: Init filter uses ConditionUtils for gamemode
- **WHEN** `Registry.init` filters the component list for a gamemode-gated class
- **THEN** the decision is produced by `ConditionUtils.passes(clazz)` and no gamemode-specific code path runs

#### Scenario: Admin command writes via the component
- **WHEN** an admin runs the `/gamemode` console command
- **THEN** the mode is persisted through `Gamemode.set(...)` and no `Registry` field is written