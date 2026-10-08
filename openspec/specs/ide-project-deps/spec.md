# ide-project-deps Specification

## Purpose
TBD - created by archiving change fix-ide-stale-project-deps. Update Purpose after archive.
## Requirements
### Requirement: Cross-module edits visible without clean

The build model SHALL expose inter-module deps as source project refs so an edit in `common`, `gateway`, `database`, or `annotation` is visible to `plugin` and `server` after save and auto-build, without `Clean Java Language Server Workspace`.

#### Scenario: Common edit reflects in plugin

- **WHEN** a developer edits a public type in `common` and saves with auto-build on
- **THEN** `plugin` sources referencing that type see the new shape without manual clean

#### Scenario: Gateway edit reflects in server

- **WHEN** a developer edits a public type in `gateway` and saves with auto-build on
- **THEN** `server` sources referencing that type see the new shape without manual clean

### Requirement: Deploy jars keep layout

The pack step SHALL still produce `plugin/build/libs/plugin.jar` with `plugin.json`, bundled `common`, `gateway`, `database`, `annotation` runtime classes, and external deps minus sqlite natives and `plugin/processor` code. `server/build/libs/application.jar` SHALL still bundle `server` plus `common` and `gateway`.

#### Scenario: Jar contents match allowlist

- **WHEN** old and new fat jars are listed via zip info
- **THEN** class and resource lists match apart from timestamps, `plugin.json` is present, and processor service file stays excluded

### Requirement: Standard Gradle outputs unchanged

`./gradlew :server:build :plugin:build` SHALL still yield `application.jar`, `plugin.jar`, and `plugin.sha256` at the same paths used by `Dockerfile`, `Dockerfile.local`, and CI.

#### Scenario: Docker paths present

- **WHEN** the Gradle build stage finishes
- **THEN** `server/build/libs/application.jar` and `plugin/build/libs/plugin.jar` plus sha exist for the runtime stage copy

