# configuration-versioning Specification

## Purpose
Enables schema version tracking in @Configuration classes, automatic overwriting of outdated configuration files with fresh code defaults, pretty-printed JSON formatting, and graceful handling of newer configuration files.
## Requirements
### Requirement: @Configuration version attribute
The `@Configuration` annotation SHALL declare an integer `version` attribute defaulting to `1`.

#### Scenario: Default version is 1
- **WHEN** a class is annotated with `@Configuration("path/to/config.json")` without an explicit `version` attribute
- **THEN** its `version` attribute evaluates to `1`

#### Scenario: Custom version specified
- **WHEN** a class is annotated with `@Configuration(value = "path/to/config.json", version = 2)`
- **THEN** its `version` attribute evaluates to `2`

### Requirement: Save configuration with version and pretty printing
`ConfigManager` SHALL write configuration files formatted as pretty-printed JSON containing the root-level `"version"` property equal to the `@Configuration.version()` value.

#### Scenario: New configuration file written
- **WHEN** `ConfigManager.process` runs for a configuration file that does not exist
- **THEN** a new JSON file is created with pretty-printed formatting
- **AND** the root JSON object contains `"version"` matching `@Configuration.version()` along with all serialized fields from the configuration instance

### Requirement: Overwrite outdated configuration on disk
`ConfigManager` SHALL compare the `"version"` field in an existing configuration JSON file against the `@Configuration.version()` value in code. If the file's version is strictly less than the code version, `ConfigManager` SHALL directly overwrite the file with the code default configuration and new version.

#### Scenario: File version is less than code version
- **WHEN** a configuration file exists with `"version": 1`
- **AND** code specifies `@Configuration(value = "...", version = 2)`
- **THEN** `ConfigManager` overwrites the file on disk with the code instance defaults and `"version": 2`
- **AND** no fields from the old file are preserved or migrated

#### Scenario: File lacks version attribute
- **WHEN** an existing configuration file has no `"version"` field
- **AND** code specifies `@Configuration(value = "...", version = 1)`
- **THEN** `ConfigManager` treats the file version as `0`
- **AND** overwrites the file on disk with code defaults and `"version": 1`

### Requirement: Load matching configuration without overwrite
`ConfigManager` SHALL load field values into the configuration instance without modifying the file when the file version equals the code version.

#### Scenario: File version matches code version
- **WHEN** a configuration file exists on disk with `"version": 2`
- **AND** code specifies `@Configuration(value = "...", version = 2)`
- **THEN** `ConfigManager` loads fields from the file into the instance
- **AND** the file on disk is not overwritten

### Requirement: Handle newer configuration file version
`ConfigManager` SHALL log a warning and load field values into the instance without overwriting when the file version on disk is greater than the code version.

#### Scenario: File version is greater than code version
- **WHEN** a configuration file exists on disk with `"version": 3`
- **AND** code specifies `@Configuration(value = "...", version = 2)`
- **THEN** `ConfigManager` logs a warning about the version mismatch
- **AND** loads available fields into the instance without overwriting the file on disk

