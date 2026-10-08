# plugin-jar-lifecycle Specification

## Purpose
TBD - created by archiving change harden-reconcile-and-sweep. Update Purpose after archive.
## Requirements
### Requirement: Plugin jar hash computed once at start

The plugin SHALL compute the `mods/plugin.jar` SHA-256 once and cache it for the process lifetime, and the `get-state` snapshot SHALL report that cached hash. The plugin SHALL NOT re-read or re-hash the jar on later `get-state` calls, because the manager may have replaced the file with a jar that is not the one currently loaded.

#### Scenario: Hash reflects the loaded jar
- **WHEN** the manager replaces `mods/plugin.jar` while the plugin is running
- **THEN** `get-state` still reports the hash computed at start, not the replaced file

#### Scenario: Hash computed a single time
- **WHEN** `get-state` is called repeatedly
- **THEN** the jar is hashed at most once for the whole process

### Requirement: Manager owns plugin update timing

The system SHALL track exactly one managed plugin (the controller, `mods/plugin.jar`); the `PluginData` multi-plugin abstraction SHALL NOT exist. The manager reconcile loop SHALL own update timing. Jar drift SHALL be resolved by a full recreate, which writes the bundled `mods/plugin.jar` during its host step. The plugin SHALL NOT poll, SHALL NOT keep a pending hash, SHALL NOT download bundle bytes, SHALL NOT decide restart time, and SHALL NOT be sent a standalone restart order for a jar.

#### Scenario: Jar drift recreates and ships the new jar
- **WHEN** the manager detects jar drift on an empty node
- **THEN** it recreates the box, the host step writes the bundled `mods/plugin.jar`, and the fresh container loads the new jar

#### Scenario: No in-plugin updater
- **WHEN** the plugin source is searched for a poll, a pending-hash field, or a download path
- **THEN** no such code exists

### Requirement: Restart is a direct unload

The plugin SHALL expose restart as firing `UnloadServerEvent(exit=true)` directly. The client and server `restart` commands SHALL fire that event inline. There SHALL be no `PluginUpdater` class, no `ApiGateway.restart()` method, and no plugin `restart` RPC handler. The client `restart` command SHALL live in the `admin` package; the `plugin.update` package SHALL NOT exist.

#### Scenario: Manual restart exits inline
- **WHEN** the client or server `restart` command runs
- **THEN** it fires `UnloadServerEvent(exit=true)` directly with no updater wrapper

#### Scenario: No dead restart plumbing
- **WHEN** the source is searched for `PluginUpdater`, `ApiGateway.restart`, or a plugin `restart` RPC handler
- **THEN** no matches exist

