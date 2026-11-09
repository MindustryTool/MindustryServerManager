# component-condition-consistency

## Requirements

### Requirement: Component dependency conditions are covered at build time
The annotation processor SHALL fail compilation when a `@Component` depends on another `@Component` whose `@ConditionOn` set is not a subset of the dependent's own `@ConditionOn` set, where set membership matches by condition class and `args` equality. Dependency edges SHALL include selected-constructor parameters and non-`@Param` parameters of `@ClientCommand`/`@ServerCommand` methods. The selection of the constructor SHALL mirror `Registry.selectConstructor` (single declared constructor, else no-arg), with final uninitialized fields used as a fallback for Lombok-generated constructors. The check SHALL be syntactic only; it SHALL NOT instantiate or invoke `Condition` implementations.

#### Scenario: Uncovered constructor edge fails the build
- **WHEN** a `@Component` with no `@ConditionOn` declares a selected constructor parameter typed as a `@Component` with `@ConditionOn(OnOfficial.class)` (no args)
- **THEN** compilation fails with an error naming the dependent class, the dependency class, and the missing condition

#### Scenario: Covered constructor edge passes
- **WHEN** both the dependent and the dependency declare `@ConditionOn(Cfg.OnOfficial.class)` with no args
- **THEN** compilation succeeds

#### Scenario: Covered with extra dependent conditions passes
- **WHEN** the dependent declares two `@ConditionOn` conditions including the dependency's `@ConditionOn(Cfg.OnOfficial.class)`
- **THEN** compilation succeeds

#### Scenario: Different args do not cover each other
- **WHEN** the dependent declares `@ConditionOn(value = GamemodeCondition.class, args = {"catali"})` and the dependency declares `@ConditionOn(value = GamemodeCondition.class, args = {"flood"})`
- **THEN** compilation fails

#### Scenario: Different condition kinds do not cover each other
- **WHEN** the dependent declares `@ConditionOn(Cfg.OnHub.class)` and the dependency declares `@ConditionOn(Cfg.OnOfficial.class)`
- **THEN** compilation fails

#### Scenario: Uncovered command method parameter fails the build
- **WHEN** a `@ClientCommand`/`@ServerCommand` method on a `@Component` declares a non-`@Param` parameter whose type is a `@Component` with conditions not covered by the containing class
- **THEN** compilation fails

#### Scenario: Non-component and getOrNull dependencies are ignored
- **WHEN** a `@Component` depends on a non-`@Component` type, or accesses a conditional service only via `Registry.getOrNull` in a method body
- **THEN** compilation succeeds (no edge is recorded for those usages)

### Requirement: Command and menu availability follows class-level gating
Official-only menus gated with the matching `@ConditionOn` SHALL NOT be instantiated on servers where the condition fails, and their client commands SHALL NOT be registered. Menu null-branch fallbacks for absent services SHALL be removed; no "unavailable" message is shown for gated menus.

#### Scenario: Community server boot does not crash
- **WHEN** the plugin starts with `IS_OFFICIAL` unset or not `"true"`
- **THEN** the plugin starts without `Registry init failed`
- **AND** `TaskMenu`, `ShopMenu`, `GachaMenu` are not present in the registry
- **AND** commands `/shop`, `/gacha`, `/tasks` are unknown commands

#### Scenario: Official server behavior is unchanged
- **WHEN** the plugin starts with `IS_OFFICIAL=true`
- **THEN** `TaskMenu`, `ShopMenu`, `GachaMenu` are registered and their commands work as before
