# node-data-host-path

## Purpose

Provides a daemon-visible override for the node data directory used only as the `Bind` source for game containers. Blank keeps prod behavior. Local compose sets it so manager writes and game mounts share one real folder.

## Requirements

### Requirement: Daemon-visible bind source

The manager SHALL derive game bind-mount sources from the `NODE_DATA_HOST_PATH` environment variable when set, falling back to the container data path when blank, and SHALL use the resolved value only for mount sources while keeping all file reads/writes on the container data path.

#### Scenario: Explicit host path wins for mounts

- **WHEN** `NODE_DATA_HOST_PATH` is set to a non-blank path at container create time
- **THEN** the game `/config` bind source is built from that path joined with `servers/<serverId>/config`

#### Scenario: Blank env keeps prod behavior

- **WHEN** `NODE_DATA_HOST_PATH` is missing or blank
- **THEN** the bind source equals today's derived path from the container data dir

#### Scenario: Explicit value never blank-injected

- **WHEN** the resolver runs with any input
- **THEN** it never returns a blank path; blank input falls back and explicit input is trimmed

### Requirement: Local compose reunites both views

The local compose file SHALL set `NODE_DATA_HOST_PATH` to the absolute Windows data path so the daemon-visible bind source and the manager's mounted `./data` name the same real folder.

#### Scenario: Game sees manager-written files

- **WHEN** the manager writes `server.json` and overwrites `mods/plugin.jar` from its bundled copy in `host()`, then a game container is created via local compose
- **THEN** `/config/server.json` and `/config/mods/plugin.jar` inside the game contain the same bytes