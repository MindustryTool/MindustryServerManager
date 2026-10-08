# plugin-updater-gamemode-restart Specification

## Purpose

Manages the single managed plugin (controller, `mods/plugin.jar`). The manager owns update timing and restart; the plugin exits on manager order.

## Requirements

### Requirement: Triggering Update Download and Server Restart

The system SHALL track exactly one managed plugin (the controller, `mods/plugin.jar`); the `PluginData` multi-plugin abstraction SHALL NOT exist. The manager reconcile loop SHALL own update timing and SHALL write the bundled `mods/plugin.jar` to the node before ordering the restart. The plugin SHALL NOT poll, SHALL NOT keep pending hash, SHALL NOT download bundle bytes, and SHALL NOT decide restart time. On manager restart order the plugin SHALL exit via `UnloadServerEvent(exit=true)`, relying on the container restart policy to reload the newly written jar. Other plugins on the node are user-managed and SHALL be ignored by the updater.

#### Scenario: Manager ordered restart applies jar
- **WHEN** the manager detects jar drift on an empty node
- **THEN** it writes the bundled bytes to `mods/plugin.jar`, sends `restart`, and the plugin exits so the container reloads the new jar