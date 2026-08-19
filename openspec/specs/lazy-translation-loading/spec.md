# lazy-translation-loading

## Purpose

TBD - created by syncing change optimize-startup-performance. Update Purpose after implementation.

## Requirements

### Requirement: Catalogs load lazily on first use
The plugin SHALL NOT preload any translation catalog during startup. Each catalog SHALL be loaded on demand, at most once per language, at the moment `Tr` first needs it.

#### Scenario: No catalogs loaded at startup
- **WHEN** the plugin initializes
- **THEN** no translation catalog is read or loaded during `TranslationLoader.init` or the component scan
- **AND** `Tr` lookups before any load complete use the existing locale fallback chain (region → base → `en` → raw key)

#### Scenario: First lookup triggers load
- **WHEN** `Tr` resolves a key for a locale whose catalog is not yet loaded
- **THEN** the catalog for that language is read and merged into `TrCatalog` before the lookup is answered

#### Scenario: Each language loads at most once
- **WHEN** a language's catalog was already loaded (or attempted and failed)
- **THEN** subsequent lookups do not attempt to load it again

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