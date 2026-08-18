# locale-key-parity

## Purpose

TBD - created by syncing change add-locale-key-parity-test. Update Purpose after implementation.

## Requirements

### Requirement: Key parity against English baseline

The plugin SHALL verify, via an automated test, that every non-English locale catalog in `i18n/` contains exactly the same set of dotted translation keys as `i18n/en.json`. `en.json` SHALL be treated as the canonical baseline. The verification SHALL flatten each catalog's nested JSON into dotted keys using the same semantics as `TrCatalog` (each segment matches `[a-z0-9_.]+`).

#### Scenario: Missing key in a non-English catalog
- **WHEN** `i18n/vi.json` does not contain a dotted key that exists in `i18n/en.json`
- **THEN** the test fails and reports the missing key and the affected language file

#### Scenario: Extra key in a non-English catalog
- **WHEN** a non-English catalog contains a dotted key that does not exist in `i18n/en.json`
- **THEN** the test fails and reports the extra key and the affected language file

#### Scenario: All catalogs in parity
- **WHEN** every non-English catalog in `i18n/` has exactly the same dotted key set as `en.json`
- **THEN** the test passes

#### Scenario: New locale files are covered automatically
- **WHEN** a new `i18n/<language>.json` file is added to the resources
- **THEN** it is automatically included in the parity verification without modifying the test

### Requirement: Catalog load warnings fail the parity test

Before comparing key sets, the parity test SHALL load each catalog through `TrCatalog` and SHALL fail if any catalog produces validation warnings (malformed JSON, invalid key segments, or non-string values), because a warned-away entry would otherwise be a silent false pass.

#### Scenario: Malformed catalog detected
- **WHEN** a locale file fails to parse or contains invalid key segments
- **THEN** the parity test fails and reports the warnings instead of comparing incomplete key sets
