# server-plugin-proxy Specification

## Purpose

Manages plugin version queries and binary downloads from game server nodes via WebSocket, serving the manager-local bundled plugin.

## Requirements

### Requirement: Server Plugin Version Query Proxy

The server manager SHALL expose a WebSocket message handler for `"get-plugin-version"` that takes no meaningful input and returns the SHA-256 hash of the manager-bundled `plugin.jar` as a plain string. The manager SHALL answer from its in-memory bundle without issuing any upstream HTTP request. The `owner`/`repo`/`tag` coordinates and the `updatedAt` plumbing SHALL NOT exist.

#### Scenario: Plugin queries version

- **WHEN** a plugin sends `"get-plugin-version"`
- **THEN** the server manager responds with the bundled jar hash string, without any upstream HTTP request

#### Scenario: Repeated version queries stay local

- **WHEN** a plugin sends `"get-plugin-version"` multiple times
- **THEN** the server manager returns the in-memory bundled hash every time without issuing an upstream HTTP request

### Requirement: Server Plugin Binary Download Proxy

The server manager SHALL expose a WebSocket message handler for `"download-plugin"` that takes no meaningful input and returns the manager-bundled `plugin.jar` bytes. The manager SHALL serve the bytes from its in-memory bundle without issuing any upstream HTTP request.

#### Scenario: Plugin downloads jar

- **WHEN** a plugin sends `"download-plugin"`
- **THEN** the server manager responds with the bundled `plugin.jar` bytes without re-downloading from any upstream API

#### Scenario: Repeated downloads stay local

- **WHEN** multiple plugins request `"download-plugin"`
- **THEN** the server manager serves the in-memory bundled bytes to all requests without issuing upstream HTTP requests