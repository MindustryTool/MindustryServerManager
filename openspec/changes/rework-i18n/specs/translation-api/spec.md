## ADDED Requirements

### Requirement: Tr public API

The plugin SHALL provide a `plugin.utils.Tr` class exposing translation entry points:
- `t(Locale locale, String key, Object... args)`
- `t(Session session, String key, Object... args)`
- `t(Player player, String key, Object... args)`

The `args` varargs SHALL be alternating `name, value` pairs used to fill named placeholders. The `Session`/`Player` overloads SHALL resolve the locale from the session's locale / `Utils.parseLocale(player.locale)` respectively.

#### Scenario: Locale-based lookup
- **WHEN** `Tr.t(locale, "vote.failed")` is called with an `en` locale
- **THEN** the `vote.failed` value from the `en` catalog is returned

#### Scenario: Player-based lookup resolves locale
- **WHEN** `Tr.t(player, "hub.not_found")` is called
- **THEN** the lookup uses `Utils.parseLocale(player.locale)` as the requested locale

#### Scenario: Alternating args fill placeholders
- **WHEN** `Tr.t(locale, "vote.timeout", "time", 10, "unit", "s")` is called
- **THEN** `{time}` and `{unit}` in the value are replaced with `10` and `s`

### Requirement: Config message fallback

The plugin SHALL provide a fallback-text overload `tWithFallback(Locale locale, String key, Object fallbackText, Object... args)`. When the key is absent in every fallback level, the plugin SHALL return the interpolated `fallbackText` instead of the raw key. When the key exists in any catalog, the catalog value SHALL win over the config fallback.

> Note: the fallback overload is named `tWithFallback` (not `t`) because an overload named `t(Locale, String, Object, Object...)` is ambiguous with `t(Locale, String, Object...)` at call sites passing 3+ args (JLS most-specific resolution fails for variable-arity overloads of different arity).

#### Scenario: Key present overrides config
- **WHEN** `welcome.message` exists in a catalog and `tWithFallback(locale, "welcome.message", cfgText)` is called
- **THEN** the catalog value is returned

#### Scenario: Key absent uses config text
- **WHEN** `welcome.message` is absent from all catalogs and `tWithFallback(locale, "welcome.message", cfgText)` is called
- **THEN** the interpolated `cfgText` is returned

### Requirement: I18n removal

The plugin SHALL remove `plugin.utils.I18n` and its `@`-marker logic. No source file SHALL reference `I18n.t(...)` or the `I18n` class after migration.

#### Scenario: No I18n references remain
- **WHEN** the plugin source tree is inspected
- **THEN** there is no reference to `plugin.utils.I18n` anywhere in `plugin/src/main/java/plugin/**`
- **AND** the project compiles with only the `Tr` API

### Requirement: All call sites migrated

Every message previously produced through `I18n.t(...)` SHALL be produced through `Tr.t(...)` with a semantic module key, and its English text (including any color codes) SHALL exist in `i18n/en.json` under the matching namespace.

#### Scenario: Every message has a catalog entry
- **WHEN** a `Tr.t(locale, "module.key", ...)` call exists
- **THEN** `i18n/en.json` contains `module.key` with the English text
- **AND** the resulting in-game message is equivalent to the pre-migration message