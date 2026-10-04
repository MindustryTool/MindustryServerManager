## ADDED Requirements

### Requirement: Server Plugin Version Query Proxy
The server manager SHALL expose a WebSocket message handler for `"get-plugin-version"` that receives plugin repository coordinates (`owner`, `repo`, `tag`) and returns a `PluginVersionDto` containing release metadata. The server manager SHALL cache version query results in memory with a 5-minute expiration time, returning cached values on hits and querying the upstream plugin version API on cache misses.

#### Scenario: Plugin queries version with cache miss
- **WHEN** a plugin sends `"get-plugin-version"` for owner "MindustryTool", repo "MindustryServerManager", tag "plugin" and no valid cache entry exists
- **THEN** the server manager fetches the version from the upstream API, caches the result for 5 minutes, and responds to the plugin with the `PluginVersionDto`

#### Scenario: Plugin queries version with cache hit
- **WHEN** a plugin sends `"get-plugin-version"` within 5 minutes of a previous fetch for the same owner, repo, and tag
- **THEN** the server manager returns the cached `PluginVersionDto` without issuing an upstream HTTP request

### Requirement: Server Plugin Binary Download Proxy
The server manager SHALL expose a WebSocket message handler for `"download-plugin"` that receives plugin repository coordinates (`owner`, `repo`, `tag`) and returns the plugin `.jar` binary as a byte array. The server manager SHALL cache the downloaded binary in memory with a 5-minute expiration time.

#### Scenario: Plugin downloads jar with cache miss
- **WHEN** a plugin sends `"download-plugin"` for a plugin and no valid binary cache entry exists
- **THEN** the server manager downloads the jar binary from the upstream API, caches the byte array for 5 minutes, and responds to the plugin with the byte array

#### Scenario: Plugin downloads jar with cache hit
- **WHEN** multiple plugins request `"download-plugin"` for the same owner, repo, and tag within 5 minutes
- **THEN** the server manager serves the cached byte array to all subsequent requests without re-downloading from the upstream API
