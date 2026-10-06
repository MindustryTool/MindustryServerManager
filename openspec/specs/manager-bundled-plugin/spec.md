# manager-bundled-plugin Specification

## Purpose

Bakes `plugin.jar` into the manager image and handles bundle operations at runtime.

## Requirements

### Requirement: Manager image contains versioned plugin bundle

The manager image SHALL contain the `plugin.jar` built from the same commit plus its SHA-256 hash at a known in-image path. The manager SHALL fail fast at startup when the bundle or hash is missing.

#### Scenario: Bundle present at startup

- **WHEN** the manager starts with `plugin.jar` and its hash file present in the image
- **THEN** the manager loads the bytes and hash into memory and serves game nodes from them

#### Scenario: Bundle missing at startup

- **WHEN** the manager starts without `plugin.jar` or its hash file in the image
- **THEN** startup fails with a clear error instead of running unable to serve plugins

### Requirement: Overwrite plugin jar on host

`ServerService.host()` SHALL overwrite the node's `mods/plugin.jar` with the bundled copy immediately after writing `server.json`, unconditionally and without comparing hashes.

#### Scenario: Host installs the bundled plugin

- **WHEN** `host-server` runs for any node, regardless of what `mods/plugin.jar` currently contains
- **THEN** the manager replaces it with the bundled bytes before creating the game container

### Requirement: Manager build covers plugin sources

A change to `plugin/**` or its shared modules (`dto`, `gateway`, `database`, `annotation`) SHALL trigger a manager image build, and no `plugin` GitHub Release SHALL be published.

#### Scenario: Plugin-only push rebuilds the manager

- **WHEN** a push to `main` touches only `plugin/**`
- **THEN** the manager image is rebuilt with the new bundled `plugin.jar` and no GitHub Release is created