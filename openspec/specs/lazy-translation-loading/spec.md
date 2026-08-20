# lazy-translation-loading

## Purpose

Avoid reading every translation catalog at startup. Catalogs are loaded per language on demand, and the on-demand loader is registered at `Tr` class-load time so a lookup at any point triggers the load regardless of component initialization order.

## Requirements

### Requirement: Catalogs load lazily on first use
The plugin SHALL NOT preload any translation catalog during startup. Each catalog SHALL be loaded on demand, at most once per language, at the moment `Tr` first needs it. The on-demand loader SHALL be registered before any lookup can occur — at `Tr` class-load time, independent of component initialization order — so a lookup at any point during or after startup SHALL trigger the load rather than fall through to the raw key.

#### Scenario: No catalogs loaded at startup
- **WHEN** the plugin initializes
- **THEN** no translation catalog is read or loaded during the component scan
- **AND** `Tr` lookups before any load complete use the existing locale fallback chain (region → base → `en` → raw key)

#### Scenario: First lookup triggers load
- **WHEN** `Tr` resolves a key for a locale whose catalog is not yet loaded
- **THEN** the catalog for that language is read and merged into `TrCatalog` before the lookup is answered

#### Scenario: Each language loads at most once
- **WHEN** a language's catalog was already loaded (or attempted and failed)
- **THEN** subsequent lookups do not attempt to load it again

#### Scenario: Loader available from the very first lookup
- **WHEN** a `Tr` lookup occurs before `TranslationLoader`'s component initialization would have run (e.g. from another component's `@Init` or a class initializer), for a key that exists in the language's catalog
- **THEN** the lookup triggers the on-demand load and returns the translated value, not the raw key

#### Scenario: No component-init dependency
- **WHEN** the plugin's component scan completes
- **THEN** no component registration or `@Init` ordering is required for the on-demand loader to be active

### Requirement: Loader registered at class load
`Tr` SHALL install its on-demand catalog loader in a static initializer so that the loader exists before any static method or field of `Tr` is used.

#### Scenario: First use of Tr resolves from classpath
- **WHEN** `Tr.t(locale, "hub.not_found")` is the very first use of `Tr` in a fresh process
- **THEN** the `hub.not_found` value from the classpath `i18n/<lang>.json` catalog is returned

### Requirement: Catalog load failures degrade gracefully
Any IO error or malformed catalog encountered during an on-demand load SHALL be logged as a warning and SHALL NOT crash the server; lookups continue through the fallback chain.

#### Scenario: Missing catalog file
- **WHEN** a requested language's catalog file does not exist
- **THEN** a warning is logged once
- **AND** lookups for that locale resolve through region → base → `en` → raw key

#### Scenario: Corrupt catalog
- **WHEN** a catalog file is unreadable or invalid JSON
- **THEN** the error is logged as a warning
- **AND** no partial entries become visible to `Tr`

### Requirement: Lookup concurrency is safe
`TrCatalog` SHALL tolerate concurrent lookups while a catalog is being loaded lazily.

#### Scenario: Concurrent first lookups
- **WHEN** multiple threads request a locale whose catalog is not yet loaded
- **THEN** exactly one load is performed
- **AND** every caller either sees the loaded catalog or falls back safely