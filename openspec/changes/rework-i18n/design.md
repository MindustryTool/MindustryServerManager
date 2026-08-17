## Context

`plugin.utils.I18n.t(...)` is a passthrough: `@`-prefixed args have the marker stripped and are returned verbatim. There is no catalog, locale lookup, fallback, or interpolation, and the ~129 call sites mix color codes, literal text, and values into one varargs list. This change introduces a real JSON-backed translation system and migrates every call site.

## Goals / Non-Goals

**Goals:**
- JSON catalogs (nested objects, module-based namespaces) under `plugin/src/main/resources/i18n/`, one file per base language.
- Keys restricted to `[a-z0-9.]` (dotted paths into nested JSON).
- Named `{placeholder}` interpolation in catalog values; Mindustry color codes live inside catalog values.
- Fallback chain: requested locale → base language → `en` → raw key.
- Load-time warnings (never a crash) for malformed keys/values.
- Config-driven messages keep `Cfg` as fallback with per-locale catalog override.
- A new `Tr` class replaces `I18n`; all ~129 call sites migrated in this change; old `I18n` deleted.

**Non-Goals:**
- No pluralization/grammar rules (ICU/MessageFormat). Keep interpolation simple.
- No region-specific files (`en-US.json`); only base-language files with fallback.
- No per-message inline key syntax in strings (e.g. no embedded `@` markers).
- No server-side translation editing UI.

## Decisions

### 1. New `Tr` class in `plugin.utils`; delete `I18n`
Introduce `plugin.utils.Tr` with the overloads:
- `t(Locale locale, String key, Object... args)` — args are alternating `name, value` pairs for named placeholders.
- `t(Session session, String key, Object... args)` and `t(Player player, String key, Object... args)` — resolve locale via existing `Utils.parseLocale` / `session.locale`.
- `t(Locale locale, String key, Object fallbackText, Object... args)` — used by config messages: if the key is absent in every catalog level, the interpolated `fallbackText` is returned.

`plugin.utils.I18n` and its `@`-marker logic are removed; all references are migrated.

**Rationale:** The user chose a new class over reusing `I18n`; a clean API surface with explicit keys avoids inheriting the mixed varargs/`@` conventions.
**Alternative considered:** keeping `I18n.t` name with new internals — rejected by decision; a new class makes stale `@`-style calls fail at compile time.

### 2. Catalog files and in-memory model
Layout under `plugin/src/main/resources/i18n/`:
```
i18n/
  en.json
  vi.json
  ...
```
Each file is nested JSON by module namespace:
```json
{
  "vote": {
    "failed": "[scarlet]Vote failed, not enough votes.",
    "timeout": "[orange]Vote timeout in {time} {unit}"
  },
  "hub": {
    "not_found": "Hub not found"
  }
}
```
Key `vote.timeout` resolves to the nested value. At init, every `i18n/<lang>.json` is loaded and flattened into `Map<String /*language tag*/, Map<String /*dotted key*/, String>>`. Files are read via the classpath/arc internal files API; a missing file for a referenced base language is a warn, not a failure.

**Rationale:** Nested objects are readable and group keys by module (user decision); flattening at load gives O(1) lookup.
**Alternative considered:** flat dotted keys (`"vote.failed": "..."`) and per-namespace files — rejected by user decision (nested objects).

### 3. Key format and validation
A key is a dotted path where every segment matches `[a-z0-9]+` (so the whole key matches `[a-z0-9.]+`). At load, the loader walks each catalog:
- warns (via `Log.warn`) on any non-object/non-string value or segment that violates the format;
- ignores the offending entry but continues loading.

**Rationale:** The `[a-z0-9.]` constraint makes keys predictable and safe to use as map lookups and in logs (user decision: warn only, never crash).

### 4. Locale resolution and fallback
Given a `Locale`, lookup order is:
1. `locale.toLanguageTag()` (e.g. `en-US`) — normally absent since only base files exist;
2. `locale.getLanguage()` (e.g. `en`);
3. `en`;
4. return the raw key (or the `fallbackText` for config-message overloads).

`Utils.parseLocale` already normalizes Mindustry locale strings (`en_US` → `en-US`), so this chain is applied after that normalization.

**Rationale:** Base-language files plus a three-step fallback cover region codes without multiplying catalog files (user decision).

### 5. Named placeholders and colors
Catalog values may contain `{name}` tokens. `t(locale, key, args...)` replaces each `{token}` with its value using `String.replace`. Color codes are part of the catalog value (user decision), so they are present before interpolation and pass through untouched. Values passed in args (player names, numbers, map ids) are inserted verbatim; callers may wrap them in color codes if needed.

Example:
```java
Tr.t(locale, "vote.timeout", "time", time, "unit", "s");
// catalog: "[orange]Vote timeout in {time} {unit}" → "[orange]Vote timeout in 10 s"
```

**Rationale:** Named placeholders keep translations reorderable per language (user decision); keeping colors in values lets each locale control its own styling.

### 6. Config-driven messages
`Cfg.WELCOME_MESSAGE` and `Cfg.CHOOSE_SERVER_MESSAGE` become catalog keys with the config constant as fallback:
```java
Tr.t(session.locale, "welcome.message", Cfg.WELCOME_MESSAGE);
Tr.t(session.locale, "hub.choose_server", Cfg.CHOOSE_SERVER_MESSAGE);
```
The `fallbackText` overload returns the config value (interpolated) only when the key is missing in all catalog levels. Adding the key to a catalog file overrides the config for that locale (user decision: config default, catalog override).

**Rationale:** Keeps config usable for servers that want their own text while allowing locale-specific defaults.

### 7. Migration strategy
Every `I18n.t(...)` call site is converted to a `Tr.t(...)` call with a semantic module key, and the English text (with colors) is added to `en.json` under the matching namespace. Messages with interpolation use named placeholders. The old `I18n` class is deleted only after all references are gone. Migration is grouped by module in the task list.

**Rationale:** Full migration in one change (user decision) keeps a single consistent state and lets the compiler catch every stale `I18n` reference.

### 8. Testability: pure-Java catalog core
The catalog parsing, validation, fallback, and interpolation live in an arc/Mindustry-free class (e.g. `plugin.utils.TrCatalog`) that works on plain `String` JSON input. The `Tr` facade and the resource loader only handle locale resolution and reading `i18n/*.json` via arc's file API; they delegate to `TrCatalog`. This keeps the tested surface runnable under plain JUnit 5 without a Mindustry runtime.

**Rationale:** Mindustry/arc are `compileOnly` dependencies, so tests cannot execute code that touches them. Isolating the pure logic makes the whole system verifiable.
**Alternative considered:** testing only via a live server — rejected, not automatable in CI.

## Testing strategy

- Enable the commented-out JUnit 5 dependencies in `plugin/build.gradle` and a `test` source set.
- Unit tests cover: key-format validation (invalid segments skipped), dotted-key resolution, fallback chain (`en-US` → `en`, unknown → `en` → raw key), `{placeholder}` interpolation with color preservation, and the config `fallbackText` behavior (absent key → fallback, present key → catalog wins).
- A catalog-loading test parses an `en.json` fixture through `TrCatalog` and asserts nested values resolve by dotted key.
- CI: update `.github/workflows/build-plugin.yml` to uncomment/enable the `./gradlew :plugin:test` step and upload the test report (runs on every push to `main` touching the plugin).

## Risks / Trade-offs

- **Large mechanical diff (~129 sites + en.json)** → grouped by module; compile errors after deleting `I18n` enumerate every missed site.
- **Colors in catalog values** → translations must be authored with Mindustry color codes; the loader validates only structure, not color syntax. Mitigated by keeping `en.json` values verbatim copies of current strings.
- **Config fallback text with placeholders** → `fallbackText` is interpolated with the same args, so config text supports `{token}` too.
- **Trail messages** (`TrailMenu` uses `req.getMessage()` at runtime) → these are dynamic strings, not fixed keys; they are sent as-is (treated as already-localized literal text), while surrounding UI strings use keys. Flagged in tasks.
- **Interpolation collision** → a value literally containing `{...}` would be treated as a placeholder. Accepted; values are authored by us and placeholders are known.

## Migration Plan

1. Add `Tr` + catalog loader + `en.json` scaffolding, load catalogs at init.
2. Migrate call sites module-by-module (hub, vote, session, grief, tip, maprating, trail, admin, security, welcome, event handler, command handlers).
3. Delete `I18n`; resolve any remaining compile errors.
4. Add `vi.json` (or another) as a proof that per-locale files work.
5. Build and spot-check a few locales.

## Open Questions

- Which second locale file to ship as the demonstration (e.g. `vi.json`)? Default assumption: `vi.json` matching the plugin's existing player base; can be a stub with a few keys.