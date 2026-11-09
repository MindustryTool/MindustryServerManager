## Why

When plugin configuration schemas or default values change in code, existing configuration files on disk become stale or incompatible. Currently, there is no versioning mechanism in `ConfigManager`, leaving administrators with obsolete config structures unless manually deleted. Adding a version field enables automatic overwriting of outdated configuration files with fresh defaults from code without requiring manual intervention or migration scripts.

## What Changes

- Add a `version` attribute to the `@Configuration` annotation (`int version() default 1;`).
- Update `ConfigManager` to track and embed the `version` field into configuration JSON files upon creation or overwrite.
- When loading a configuration file:
  - If `fileVersion < codeVersion` (including unversioned legacy files treated as version 0), directly overwrite the file with code defaults and the new version.
  - If `fileVersion == codeVersion`, load fields into the instance normally.
  - If `fileVersion > codeVersion`, log a warning and load fields into the instance normally.
- Write configuration JSON files with pretty-printed formatting for human readability.

## Capabilities

### New Capabilities
- `configuration-versioning`: Schema version tracking and automatic code-default overwriting for `@Configuration` files.

### Modified Capabilities
<!-- None -->

## Impact

- `annotation`: `plugin.annotations.Configuration` adds `int version() default 1;`.
- `plugin`: `plugin.core.ConfigManager` handles version checking, pretty printing, and code-default overwriting.
- Existing config files on disk with no `version` field will be overwritten on startup if the code defines `version >= 1`.
