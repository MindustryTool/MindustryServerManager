## Context

VS Code with RedHat Java Build Server shows stale cross-module types. `plugin/.classpath` holds both `build/libs/common-0.0.1-SNAPSHOT.jar` and `src /common` entries. JDT favors the jar. Edits in `common`, `gateway`, `database`, `annotation` miss until Clean.

Current state:
- `plugin/build.gradle.kts:47-96` overrides `jar` to be fat via `from(otherProject.output)` plus hand `runtimeClasspath` merge.
- `server/build.gradle.kts:57-60` sets `jar.enabled=false` and relies on `shadowJar` as `application.jar`.
- Mixed Java config: `common`, `database`, `annotation`, `plugin` use `targetCompatibility 17`. `server`, `gateway` use `toolchain 17`.
- Deploy expects `plugin/build/libs/plugin.jar` + `plugin.sha256` and `server/build/libs/application.jar` via `Dockerfile`, `Dockerfile.local`, `build-plugin.yml`, `PluginBundleService`.

## Goals / Non-Goals

**Goals:**
- Dependents resolve `project(":common")` etc as source in IDE, not stale jars.
- Keep deploy jars byte-content same in class list. Keep `plugin.json`, excludes, sqlite native strips.
- Keep Docker and CI paths unchanged. `build` still yields both fat jars.
- Unify toolchain to 17 to cut Build Server noise.

**Non-Goals:**
- No Shadow migration for `plugin`. No new deploy layout.
- No VS Code settings change in repo. No workflow switch to IntelliJ.
- No product behavior change. No API change.

## Decisions

- **A1 split thin vs fat over Shadow unify.** Thin default `jar` stays IDE-clean. Fat pack task runs only at pack time. Shadow unify was alt. Rejected: higher risk to Mindustry `plugin.jar` layout and sqlite excludes.
- **New fat task with fixed names over replacing `jar`.** Thin keeps Gradle default `*-SNAPSHOT.jar`. Fat keeps `plugin.jar` and `application.jar`. Avoids file clash. Lets `build` depend on fat while IDE models thin.
- **Wire `build`/`assemble` to fat over Docker calling fat.** Docker, `Dockerfile.local`, CI stay unchanged. Less churn. Cost: `build` does extra zip work. Acceptable.
- **Fix `plugin` + `server` together over plugin only.** Both share the anti-pattern. `server` thin restore removes `jar.enabled=false` blind spot for `gateway`/`common` refs.
- **Unify to `toolchain 17` over mixed compat.** One JDK for compile and Build Server. Less drift.

## Risks / Trade-offs

- [Risk] Fat jar misses a class after move → Mitigation: zip list diff old vs new. Gate on class list match.
- [Risk] `annotation` processor output excluded wrong → Mitigation: keep `plugin/processor/**` and processor service excludes. Check `ComponentRegistry` generates in `plugin:test`.
- [Risk] `build` slower from double jar → Mitigation: accept. Pack only runs on demand and CI.
- [Risk] Devs still stale until re-import → Mitigation: one-time Gradle reload note in change summary.
