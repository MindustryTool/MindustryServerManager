## ADDED Requirements

### Requirement: Per-locale JSON catalogs

The plugin SHALL load translation catalogs from classpath JSON files at `i18n/<language>.json` (e.g. `i18n/en.json`, `i18n/vi.json`), one file per base language. Each file SHALL be nested JSON organized by module namespace (e.g. `hub`, `vote`, `session`, `grief`, `tip`, `maprating`, `trail`, `admin`, `security`, `welcome`).

At init the plugin SHALL flatten each catalog into an in-memory map of dotted key → value. A missing catalog file for a referenced base language SHALL log a warning and SHALL NOT prevent startup.

#### Scenario: Catalog loads at startup
- **WHEN** the plugin initializes and `i18n/en.json` exists
- **THEN** every nested value is addressable by its dotted key (e.g. the `vote.timeout` entry of `{"vote": {"timeout": "..."}}`)

#### Scenario: Missing catalog file warns only
- **WHEN** a catalog file for a referenced base language does not exist
- **THEN** a warning is logged and the plugin continues with the other loaded catalogs

### Requirement: Key format validation

Translation keys SHALL match `[a-z0-9.]+`, where each dotted segment matches `[a-z0-9]+`. During catalog load, the plugin SHALL validate every entry and SHALL log a warning (never a crash) for any segment or value that violates the format or is not a string, skipping the offending entry and continuing.

#### Scenario: Invalid key is warned
- **WHEN** a catalog contains a key segment with uppercase letters, spaces, or symbols (e.g. `Vote.Failed`)
- **THEN** a warning is logged and that entry is not registered

#### Scenario: Valid key is accepted
- **WHEN** a catalog contains keys like `vote.failed` and `hub.not_found`
- **THEN** they are registered without warnings and are resolvable at runtime

### Requirement: Locale fallback chain

The plugin SHALL resolve a translation for a given `Locale` in this order: full language tag (e.g. `en-US`) → base language (e.g. `en`) → `en` → the raw key. The lookup SHALL operate on locales normalized by `Utils.parseLocale`.

#### Scenario: Region code falls back to base language
- **WHEN** a player's locale is `en-US` and no `en-US` catalog exists
- **THEN** the `en` catalog value is used

#### Scenario: Unknown locale falls back to English then key
- **WHEN** a locale has no catalog and `en` has no entry for the key
- **THEN** the raw key is returned

### Requirement: Named placeholder interpolation

Catalog values SHALL support named placeholders of the form `{token}`. When a translation is requested, every `{token}` SHALL be replaced by the value provided for that name. Mindustry color codes present in the catalog value SHALL be preserved through interpolation.

#### Scenario: Placeholders are replaced
- **WHEN** `vote.timeout` has value `"Vote timeout in {time} {unit}"` and args provide `time=10`, `unit=s`
- **THEN** the result is `"Vote timeout in 10 s"`

#### Scenario: Color codes preserved
- **WHEN** a catalog value begins with a color code like `[scarlet]` and contains a placeholder
- **THEN** the color code remains at the start of the interpolated result