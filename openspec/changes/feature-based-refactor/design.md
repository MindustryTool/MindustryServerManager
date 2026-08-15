## Context

The plugin lives in `plugin/src/main/java/plugin/**` (~150 classes) and is registered by the framework via `Registry.init("plugin")` (`Control.java`), which uses Reflections to scan the **entire** `plugin` package tree and auto-register classes annotated with `@Component`, `@Listener`, `@Schedule`, `@Init`, `@ClientCommand`, `@ServerCommand`, `@PlayerActionFilter`, `@Configuration`, `@Persistence`, and `@FileWatcher`. Constructor dependencies are auto-wired via `Registry.get(...)`; some services additionally look up `Registry.get(X.class)` lazily inside method bodies.

Today the code is organized by technical layer:

```
plugin/  Cfg, Control, PluginEvents, PluginState, Tasks
  annotations/  core/  database/  json/
  commands/{client,server}/  menus/  event/  type/  utils/  service/
  gamemode/{attack,catali,flood,sandbox,survival,ziger}/
```

`gamemode/*` is already feature-oriented. The top-level layers mix several distinct features, and two aggregators (`ClientCommands`, `ServerCommands`) own commands for many features. The tile-logger spec (`openspec/specs/tile-logger/spec.md`) already exists; its requirements are unaffected — `TileLogger` simply relocates to the `grief` feature.

External module `dto/` (`dto.*`, `events.*` — server-manager protocol DTOs) is untouched.

## Goals / Non-Goals

**Goals:**
- Repackage all feature-specific code into feature packages (`session`, `vote`, `grief`, `host`, `maprating`, `hub`, `security`, `trail`, `tip`, `welcome`, `update`, `gateway`, `admin`) that own their services, commands, menus, and events.
- Centralize only genuinely shared framework/infrastructure (`core/`, `annotations/`, `database/`, `json/`, command framework, menu framework, shared utils, shared lifecycle events).
- Preserve runtime behavior exactly: command names/params/permissions, events, persistence, scheduling, DI, logging, `plugin.json`, `plugin.Control` main class.
- Keep `gamemode/*` unchanged.

**Non-Goals:**
- No feature-logic changes, no bug fixes (documented in `tasks.md` as noted, not fixed).
- No dead-code removal (`RankService`, `PlayerMetadata`) — noted in `tasks.md` only.
- No splitting of `ApiGateway`'s RPC handlers into per-feature classes (see Decision 5) — documented as a future improvement.
- No new external dependencies, no build-config changes, no test-framework activation (existing test is fully commented out).

## Decisions

**1. Feature packages own their full slice (service + commands + menus + events + types).**
Each feature package contains every class whose business responsibility belongs to it, regardless of its current `service/`, `menus/`, `type/`, `event/`, or `utils/` origin. Rationale: cohesion by responsibility beats cohesion by layer, and it directly satisfies "no global `command/ service/ event/` packages for feature-specific code". Alternative considered — keeping layers and only splitting commands — rejected: leaves the scattering problem unsolved.

**2. Exact target package layout** (below). `gamemode/**` and the infra packages (`core/`, `annotations/`, `database/`, `json/`, `commands/` framework, `menus/` framework) stay put. Class moves:

- **session/** ← `service/SessionHandler.java`, `service/SessionService.java`, `repository/SessionRepository.java`, `type/Session.java`, `type/SessionData.java`, `utils/SessionUtils.java`, `utils/ExpUtils.java`, `utils/RankUtils.java`, `service/RankService.java`, `event/SessionCreatedEvent.java`, `event/SessionRemovedEvent.java`, `commands/client/OfficialCommands.java`, `menus/PlayerInfoMenu.java`
- **vote/** ← `service/VoteService.java`, `service/VoteNewWaveService.java`, `menus/RtvMenu.java` + new `VoteCommands` (rtv, vnw)
- **grief/** ← `service/AdminService.java`, `service/GriefDetectService.java`, `service/TileLogger.java`, `menus/GriefMenu.java`, `menus/GreifLoginMenu.java` + new `GriefCommands` (grief)
- **host/** ← `service/HostService.java`, `service/MapWatcher.java`
- **maprating/** ← `service/MapRating.java`, `menus/RateMapMenu.java` + new `MapRatingCommands` (map, maps, submitmap)
- **hub/** ← `service/HubService.java`, `utils/ServerUtils.java`, `type/ServerCore.java`, `type/PaginationRequest.java`, `menus/ServerListMenu.java`, `menus/GlobalServerListMenu.java`, `menus/ServerRedirectMenu.java` + new `HubCommands` (hub, servers, redirect)
- **security/** ← `service/SecurityService.java`
- **trail/** ← `service/TrailService.java`, `menus/TrailMenu.java` + new `TrailCommands` (trail)
- **tip/** ← `service/TipService.java`
- **welcome/** ← `menus/WelcomeMenu.java` + new `WelcomeCommands` (discord, website)
- **update/** ← `PluginData.java`, `PluginUpdater.java` (from `plugin/` root) + new `UpdateCommands` (restart)
- **gateway/** ← `service/ApiGateway.java`
- **admin/** ← `commands/server/ServerCommands.java`
- **utils/** keeps only shared: `Utils.java`, `JsonUtils.java`, `HttpUtils.java`, `TimeUtils.java`, `TextWidth.java`, `CommandUtils.java`
- **commands/** keeps only `ClientCommandHandler.java`, `ServerCommandHandler.java`, `ParamException.java`
- **menus/** keeps only `PluginMenu.java`, `PluginMenuService.java`
- **event/** keeps only `PluginUnloadEvent.java`, `UnloadServerEvent.java`

Rationale: every existing class has exactly one clear owner; the map comes from a full source read + dependency audit, not guesswork. Cross-feature imports (e.g. `hub` menus → `hub.PaginationRequest`, `trail` → `session.ExpUtils`) are real dependencies and are allowed (feature → feature is fine where the dependency is genuine; cycles are avoided).

**3. Split `ClientCommands` into per-feature `@Component` command classes.**
Each `@ClientCommand` method moves, verbatim, into a small `@Component` class in its feature package, preserving name, description, `admin` flag, params, and body exactly. New classes:
`session/SessionCommands` (admin, login, me, pinfo), `vote/VoteCommands` (rtv, vnw), `grief/GriefCommands` (grief), `hub/HubCommands` (hub, servers, redirect), `maprating/MapRatingCommands` (map, maps, submitmap), `trail/TrailCommands` (trail), `update/UpdateCommands` (restart), `welcome/WelcomeCommands` (discord, website). `js` stays in `session/SessionCommands` as a session/admin surface? — **No**: `js` is a sandbox/scripting command; it moves with `update`/tooling? — **Decision**: `js` (client) is an admin tool command; it belongs in `admin/` together with the server `js`. `admin/` holds both `ServerCommands` (whole file) and a new `admin/AdminCommands` with the client `js` command. Rationale: the framework registers commands per-`@Component` by scanning annotations, so splitting is zero-risk to dispatch; centralizing command definitions with their feature is the point of this change. Alternative — leaving `ClientCommands` as one class: rejected because it is exactly the "global command aggregator" the change eliminates.

**4. `ServerCommands` moves whole into `admin/`** (file+package unchanged otherwise). Its commands (gamemode, js, kickWithReason, restart, say, setting, sql) are server-administration; keeping them in one `admin` feature is appropriate, not a global `command/` package. Rationale per Phase 5: admin console surface is a real, cross-cutting-but-cohesive concern; no feature-specific admin command exists that should instead live elsewhere (session `admin` toggle is player-side and lives in `session`).

**5. Keep `ApiGateway` monolithic in `gateway/` (do not split RPC handlers).**
`ApiGateway` is the WebSocket client to the server-manager gateway and dispatches many feature RPCs (session, vote, host, etc.). Splitting its handlers into per-feature registration points is a behavior-preserving but high-risk refactor on top of a structural refactor. Decision: move it as-is to `gateway/`, update imports; document per-feature handler extraction as a future improvement in `tasks.md`. Rationale: keep this change mechanical and reviewable; cross-feature deps converge legitimately on an integration layer.

**6. Shared infrastructure stays centralized, feature-specific utils move out.**
`ExpUtils`/`RankUtils` → `session` (player progression math/leaderboard); `ServerUtils` → `hub` (redirect helpers); `SessionUtils` → `session`. `Utils`, `JsonUtils`, `HttpUtils`, `TimeUtils`, `TextWidth`, `CommandUtils` remain in `utils/` because they are referenced across most features and gamemodes. `PluginMenu`/`PluginMenuService` remain in `menus/` as the menu framework. `PluginEvents`, `Cfg`, `PluginState`, `Tasks` remain at the package root alongside `Control` (bootstrap), keeping `plugin.json`'s `plugin.Control` untouched. Rationale: the rule is "shared only what is genuinely shared"; everything shared here is infrastructure, not feature logic.

**7. Migration order matters.** First apply package moves that have no dependents within other features (leaf features), then update imports bottom-up: infra/utils → leaf features (tip, welcome, security, trail) → mid features (maprating, hub, vote, grief, host, session) → integration (gateway, update, admin) → command splits. This keeps the tree compiling as early as possible, though since it's one commit the order only reduces edit-confusion, not risk.

**8. Reflection references are unchanged.** `Registry.init("plugin")` scans the whole `plugin` tree (Recursive is true), so moved classes are found regardless of new package. The only reflection dependency is `plugin.Control` (main class), which stays. `@Configuration`/`@Persistence` keys are name-based (class simple name or annotation `value`), which does not change with package. Verify with grep for `getPackage()`/`Class.forName`/package literals after the move.

## Risks / Trade-offs

- [Missed imports after package moves → full `:plugin:build` (exit 0) plus grep of the old package names (`plugin.service.`, `plugin.menus.`, `plugin.commands.client.`, `plugin.commands.server.`, `plugin.type.`, `plugin.event.`, `plugin.utils.`) to confirm zero stale references in `plugin/src`.
- [Command-split changes behavior → each `@ClientCommand` method copied verbatim (same name/description/admin/params/body); `@Param` annotations preserved; dispatch is annotation-driven so class relocation alone cannot alter registration.
- [`ApiGateway` cross-feature RPC dispatch → left monolithic; only imports change.
- [Reflection/annotation scanning misses → none expected (`Registry.init("plugin")` recursive); verified by test-grep for package-dependent reflection.
- [Large mechanical diff makes review hard → moves are pure renames (same code, changed package line + imports); keep the command-split methods byte-identical to originals.

## Migration Plan

1. Make all package/import edits in `plugin/src/main/java/plugin/**`.
2. Add the new per-feature `@Component` command classes; remove `ClientCommands` (its methods are distributed), move `ServerCommands` to `admin`.
3. Update cross-feature imports (`session` events, `hub.PaginationRequest`, `session.ExpUtils`, etc.).
4. Grep-verify no stale references to old packages; `.\gradlew.bat :plugin:build --console=plain` must exit 0.
5. If a rollback is needed: revert the single commit; behavior is unchanged, so no data migration is required.

## Open Questions

- None blocking. `js` (client) placement was resolved to `admin/`. `RankService`/`PlayerMetadata` dead code is noted but not removed (non-goal).