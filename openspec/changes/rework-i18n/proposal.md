## Why

The current `I18n.t(...)` does not actually translate anything: strings prefixed with `@` have the `@` stripped and are returned as-is, so every message is hardcoded English. There is no catalog, no per-locale lookup, no fallback, and no interpolation — call sites mix color codes, literal text, and runtime values into a single varargs list (~129 call sites). This makes real localization impossible.

## What Changes

- Replace `plugin.utils.I18n` with a new translation class (e.g. `plugin.utils.Tr`) backed by JSON catalogs; the old `I18n` class and its `@`-marker logic are removed.
- Catalogs: one JSON file per base language (e.g. `en.json`, `vi.json`) under `plugin/src/main/resources/i18n/`, organized as **nested objects** by module namespace (`hub`, `vote`, `session`, `grief`, `tip`, `maprating`, `trail`, `admin`, `security`, `welcome`).
- Keys resolve as dotted paths (e.g. `vote.failed`) and are restricted to **lowercase letters, numbers, and `.`** only.
- Catalog values support **named placeholders** (`{name}`, `{time}`, `{count}`) and may contain Mindustry color codes.
- Lookup fallback chain: requested locale → its base language → `en` → the raw key.
- The catalog loader **warns** (does not crash) on invalid key format or unresolved keys.
- Config-driven messages (`Cfg.WELCOME_MESSAGE`, `Cfg.CHOOSE_SERVER_MESSAGE`) keep their config value as the fallback, with a per-locale catalog key able to override it.
- Migrate all existing `I18n.t(...)` call sites to the new API in this change.

## Capabilities

### New Capabilities
- `translation-catalog`: Loading and holding per-locale JSON catalogs; key validation (`[a-z0-9.]`), locale/base-language/English fallback, and `{placeholder}` interpolation in catalog values.
- `translation-api`: The public `Tr` class and its overloads (`t(Locale, key, args)`, `t(Session, key, args)`, `t(Player, key, args)`), plus per-locale config-message overrides and migration of all call sites.

### Modified Capabilities
- (none)

## Impact

- `plugin/src/main/java/plugin/utils/I18n.java` — removed; replaced by new `Tr` class.
- New `plugin/src/main/resources/i18n/en.json` (+ initial `vi.json` or others) containing all migrated messages under module namespaces.
- `plugin/src/main/java/plugin/**` — all ~129 `I18n.t(...)` call sites across menus, services, commands, and the event handler.
- `plugin/src/main/java/plugin/Cfg.java` — config-message handling for `WELCOME_MESSAGE` / `CHOOSE_SERVER_MESSAGE` (config default + catalog override).
- `plugin/build.gradle` — no change expected (resources dir is picked up automatically).