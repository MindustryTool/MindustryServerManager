## Why

Edits in `common`, `gateway`, `database`, or `annotation` stay stale in dependent modules in VS Code. Only `Clean Java Language Server Workspace` picks them up. Root cause is `plugin/.classpath` holding both stale `build/libs/*.jar` entries and `src /common` project entries, driven by fat `from(otherProject.output)` logic and disabled thin `jar`.

## What Changes

- Keep default `jar` thin for IDE use. Move fat merge to a separate pack task for `plugin`.
- Restore thin `jar` for `server`. Keep `shadowJar` as `application.jar` for deploy.
- Wire `build`/`assemble` to still produce `plugin/build/libs/plugin.jar` and `server/build/libs/application.jar` at the same paths.
- Unify all modules to Java toolchain 17. Remove mixed `targetCompatibility` noise.
- No change to runtime layout: same `plugin.jar` + `plugin.sha256` + `application.jar` contents.

## Capabilities

### New Capabilities

- `ide-project-deps`: cross-module source edits are visible to dependents without workspace clean.

### Modified Capabilities

- None. Build outputs and runtime bundle paths stay unchanged.

## Impact

- Affected code: `plugin/build.gradle.kts`, `server/build.gradle.kts`, `common`, `gateway`, `database`, `annotation` java blocks.
- Systems: VS Code JDT Build Server model, Gradle CLI, `Dockerfile`, `Dockerfile.local`, `build-plugin.yml` CI, `PluginBundleService` bundle served to game nodes.
