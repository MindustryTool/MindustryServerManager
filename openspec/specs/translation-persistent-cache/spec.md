# translation-persistent-cache Specification

## Purpose
TBD - created by archiving change translation-resilience-sqlite-l2. Update Purpose after archive.
## Requirements
### Requirement: SQLite Persistent Translation Storage
The system SHALL provide a persistent L2 translation cache backed by an embedded SQLite database stored in the manager data volume directory.

#### Scenario: Lookup on L1 cache miss retrieves persistent entry
- **WHEN** text is translated and the key is absent from the in-memory Caffeine cache (L1)
- **AND** the key exists in the SQLite persistent cache (L2)
- **THEN** the translated response is loaded from SQLite
- **AND** the entry is inserted into the Caffeine cache
- **AND** upstream translation providers are not contacted

#### Scenario: Persisting newly translated entries
- **WHEN** an upstream provider successfully translates text
- **THEN** the result is stored in the in-memory Caffeine cache (L1)
- **AND** the result is stored in the SQLite persistent cache (L2)

#### Scenario: Persistence survives application restart
- **WHEN** translations have been written to the SQLite cache
- **AND** the application restarts with a cleared in-memory cache
- **THEN** subsequent requests for previously translated phrases are resolved from SQLite without invoking upstream providers

#### Scenario: SQLite initialization resiliency
- **WHEN** the SQLite database is initialized
- **THEN** WAL mode is enabled and the translation cache table is created if it does not already exist

