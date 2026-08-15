## Why

The plugin's source is organized by technical layer (`service/`, `commands/`, `menus/`, `event/`, `type/`, `utils/`) rather than by business responsibility. As the codebase has grown, related classes that belong to one feature (e.g. session, voting, anti-grief, hub/server-list) are scattered across many packages, making features hard to locate, reason about, and evolve. Reorganizing into feature-oriented packages aligns structure with ownership and reduces navigation/coupling friction.

## What Changes

- Restructure `plugin/src/main/java` so each business feature has its own package containing that feature's services, commands, menus, events, and types.
- Define the target feature boundaries by business responsibility:
  - `session` — player session/progression: `Session`, `SessionHandler`, `SessionService`, `SessionRepository`, `SessionUtils`, `ExpUtils`, `RankUtils`, `RankService`, `SessionCreatedEvent`, `SessionRemovedEvent`, `OfficialCommands`, `PlayerInfoMenu`.
  - `vote` — map / new-wave voting: `VoteService`, `VoteNewWaveService`, `RtvMenu`, vote client commands.
  - `grief` — anti-grief reporting & investigation: `AdminService`, `GriefDetectService`, `TileLogger`, `GriefMenu`, `GreifLoginMenu`, grief commands.
  - `host` — hosting: `HostService`, `MapWatcher`.
  - `maprating` — map rating/listing: `MapRating`, `RateMapMenu`, map commands.
  - `hub` — server list & redirect: `HubService`, `ServerUtils`, `ServerCore`, `PaginationRequest`, `ServerListMenu`, `GlobalServerListMenu`, `ServerRedirectMenu`, hub commands.
  - `security` — access control: `SecurityService`.
  - `trail` — player trail cosmetic: `TrailService`, `TrailMenu`, trail commands.
  - `tip` — welcome tips: `TipService`.
  - `welcome` — informational menus: `WelcomeMenu`.
  - `update` — plugin update/restart: `PluginData`, `PluginUpdater`, update/restart commands.
  - `gateway` — server-manager WebSocket integration: `ApiGateway`.
  - `admin` — server-management console commands: `ServerCommands` (gamemode, js, kick, say, setting, sql).
- Split the two cross-cutting command aggregators so command definitions live with their feature:
  - `ClientCommands` — move each `@ClientCommand` method into its owning feature package as a small `@Component` class (session, vote, grief, hub, maprating, trail, update, welcome).
  - `ServerCommands` — moves whole into `admin`.
- Keep genuinely shared infrastructure centralized (not feature-specific):
  - Bootstrap at package root (unchanged): `Control` (must remain `plugin.Control` — `plugin.json` main class), `Cfg`, `PluginEvents`, `PluginState`, `Tasks`.
  - `core/` framework (unchanged): `Registry`, `Scheduler`, `ConfigManager`, `EventRegistrar`, `PersistenceManager`, `FileWatcherManager`, `ActionFilterManager`.
  - `annotations/`, `database/`, `json/` (unchanged).
  - `commands/` retains only the command framework: `ClientCommandHandler`, `ServerCommandHandler`, `ParamException`.
  - `utils/` retains only genuinely shared utilities: `Utils`, `JsonUtils`, `HttpUtils`, `TimeUtils`, `TextWidth`, `CommandUtils`; feature-specific utils move into their features (`ExpUtils`, `RankUtils` → session; `ServerUtils` → hub; `SessionUtils` → session).
  - `menus/` retains only the menu framework: `PluginMenu`, `PluginMenuService`; feature menus move into their features.
  - `event/` retains only shared lifecycle events: `PluginUnloadEvent`, `UnloadServerEvent`; session events move to `session`.
- `gamemode/` and its subpackages (attack, catali, flood, sandbox, survival, ziger) are already feature-oriented and are left unchanged.
- Update all `package` declarations, imports, and reflection-based references; `Registry.init("plugin")` continues to scan the whole `plugin` tree, so no runtime registration changes are needed.
- **No behavior changes**: command names, permissions, events, persistence, scheduling, DI, logging, and public APIs are preserved. Unrelated bugs/dead code (e.g. `RankService`, `PlayerMetadata`) are only documented, not fixed.

## Capabilities

### New Capabilities
- `feature-organization`: Source package layout is feature-oriented; each business feature (session, vote, grief, host, maprating, hub, security, trail, tip, welcome, update, gateway, admin) owns its services, commands, menus, and events; only genuinely shared framework/infrastructure remains centralized; behavior is preserved.

### Modified Capabilities
<!-- None: no existing spec has behavior-level requirement changes. tile-logger requirements are unaffected (TileLogger just relocates to the grief feature). -->

## Impact

- **Code**: ~110 classes across `plugin/src/main/java/plugin/**` — package moves, import updates, new small `@Component` command classes replacing the `ClientCommands` aggregator. No source-level API changes; `plugin.Control` stays at the package root.
- **Config**: `plugin.json` unchanged (main class `plugin.Control`); `Registry.init("plugin")` package-scan path unchanged.
- **Build**: `plugin/build.gradle` source sets unchanged; the `testImplementation` deps remain commented out (existing `ExpressionParserTest.java` is fully commented out — no live tests).
- **Runtime**: identical behavior; restart required to load new classes, as with any plugin redeploy.
- **Risks**: missed import/reflection references during moves (mitigated by full compile + search of old package names); accidentally splitting `ClientCommands`/`ServerCommands` methods must not alter command names/params/behavior.