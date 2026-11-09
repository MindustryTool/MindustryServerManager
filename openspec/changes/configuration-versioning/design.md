## Context

`ConfigManager` in `plugin.core` manages loading and hot-reloading configuration classes annotated with `@Configuration(path)`. Prior to this change, `@Configuration` did not track schema versions. When defaults or configuration structures evolve in code, preexisting configuration files on disk persist unchanged and can cause runtime issues or require manual deletion.

## Goals / Non-Goals

**Goals:**
- Provide a `version` attribute on the `@Configuration` annotation (`int version() default 1;`).
- Track schema versions in the root of configuration JSON files (`"version": <number>`).
- Compare `fileVersion` on disk against `codeVersion` from `@Configuration.version()`.
- Overwrite disk configuration with fresh code defaults when `fileVersion < codeVersion` (including unversioned legacy files where `fileVersion = 0`).
- Log a warning and load as-is when `fileVersion > codeVersion`.
- Format written configuration JSON files with pretty-printed indentation for readability.

**Non-Goals:**
- Data migration or field remapping between different configuration versions (explicitly excluded).
- Creating `.bak` backup files prior to overwriting (direct overwrite requested).

## Decisions

### Decision 1: Version declared on `@Configuration` annotation
- **Choice**: Add `int version() default 1;` to `@Configuration`.
- **Rationale**: Keeps configuration classes free from boilerplate fields and adheres to single source of truth.
- **Alternatives considered**:
  - Class-level field (`public int version`): Requires boilerplate in every config class and risks state duplication.
  - Separate version metadata file: Adds unnecessary filesystem complexity.

### Decision 2: JSON version injection and pretty printing via Jackson
- **Choice**: Convert the instance to a Jackson `ObjectNode` tree, attach `"version": config.version()`, and serialize with `writerWithDefaultPrettyPrinter()`.
- **Rationale**: Does not require config classes to have a `version` field. Because `JsonUtils` is configured with `FAIL_ON_UNKNOWN_PROPERTIES = false`, reading into the target class naturally ignores the metadata `"version"` field if the class does not declare it.
- **Alternatives considered**:
  - Minified JSON (`toJsonString`): Hard for server administrators to inspect and edit.

### Decision 3: Version comparison semantics
- **Choice**:
  - `fileVersion < codeVersion`: Overwrite file with code defaults and code version.
  - `fileVersion == codeVersion`: Deserialize and load normally.
  - `fileVersion > codeVersion`: Log a warning, then deserialize and load normally.
  - Missing or non-integer `"version"` in JSON: Treated as `0`.
- **Rationale**: Ensures legacy files without `"version"` are cleanly replaced when code specifies version >= 1, while forward-compatible/downgraded versions do not fail hard unless configured.

## Risks / Trade-offs

- [Risk] Custom user configuration changes are lost when a config is overwritten upon version bump.
  → Mitigation: Intentional per project requirements; schema changes deliberately reset to fresh code defaults with no migration.
- [Risk] Initial overwrite triggering file watcher events.
  → Mitigation: In `ConfigManager.process()`, version check and overwrite execute during `load()`, which runs before `startWatcher()` registers the watch event.
