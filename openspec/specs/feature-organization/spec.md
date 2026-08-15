# feature-organization Specification

## Purpose
TBD - created by archiving change feature-based-refactor. Update Purpose after archive.
## Requirements
### Requirement: Feature-oriented package structure

The plugin SHALL organize `plugin/src/main/java/plugin/**` by business feature so that each feature owns its feature-specific classes, and only genuinely shared infrastructure remains centralized.

The feature packages SHALL be: `session`, `vote`, `grief`, `host`, `maprating`, `hub`, `security`, `trail`, `tip`, `welcome`, `update`, `gateway`, `admin`. The `gamemode` package tree SHALL remain unchanged.

Each feature package SHALL contain the feature's own classes regardless of their previous technical-layer origin:

- `session` SHALL contain `Session`, `SessionData`, `SessionHandler`, `SessionService`, `SessionRepository`, `SessionUtils`, `ExpUtils`, `RankUtils`, `RankService`, `SessionCreatedEvent`, `SessionRemovedEvent`, `OfficialCommands`, `PlayerInfoMenu`, and the client commands `admin`, `login`, `me`, `pinfo`.
- `vote` SHALL contain `VoteService`, `VoteNewWaveService`, `RtvMenu`, and the client commands `rtv`, `vnw`.
- `grief` SHALL contain `AdminService`, `GriefDetectService`, `TileLogger`, `GriefMenu`, `GreifLoginMenu`, and the client command `grief`.
- `host` SHALL contain `HostService` and `MapWatcher`.
- `maprating` SHALL contain `MapRating`, `RateMapMenu`, and the client commands `map`, `maps`, `submitmap`.
- `hub` SHALL contain `HubService`, `ServerUtils`, `ServerCore`, `PaginationRequest`, `ServerListMenu`, `GlobalServerListMenu`, `ServerRedirectMenu`, and the client commands `hub`, `servers`, `redirect`.
- `security` SHALL contain `SecurityService`.
- `trail` SHALL contain `TrailService`, `TrailMenu`, and the client command `trail`.
- `tip` SHALL contain `TipService`.
- `welcome` SHALL contain `WelcomeMenu`, and the client commands `discord`, `website`.
- `update` SHALL contain `PluginData`, `PluginUpdater`, and the client command `restart`.
- `gateway` SHALL contain `ApiGateway`.
- `admin` SHALL contain `ServerCommands` (the server commands `gamemode`, `js`, `kickWithReason`, `restart`, `say`, `setting`, `sql`) and the client command `js`.

The shared infrastructure SHALL remain centralized and SHALL NOT contain feature-specific logic:

- `plugin` package root SHALL retain only `Control`, `Cfg`, `PluginEvents`, `PluginState`, `Tasks`.
- `core`, `annotations`, `database`, `json` packages SHALL be unchanged.
- `commands` SHALL contain only `ClientCommandHandler`, `ServerCommandHandler`, `ParamException`.
- `menus` SHALL contain only `PluginMenu`, `PluginMenuService`.
- `event` SHALL contain only `PluginUnloadEvent`, `UnloadServerEvent`.
- `utils` SHALL contain only `Utils`, `JsonUtils`, `HttpUtils`, `TimeUtils`, `TextWidth`, `CommandUtils`.

#### Scenario: All feature classes relocated
- **WHEN** the plugin source tree is inspected after the refactor
- **THEN** every feature-specific class resides under its feature package listed above
- **AND** no feature-specific class remains under `plugin.commands`, `plugin.menus`, `plugin.event`, `plugin.type`, `plugin.service`, or `plugin.utils`

#### Scenario: Shared infrastructure is generic only
- **WHEN** inspecting `plugin.commands`, `plugin.menus`, `plugin.event`, and `plugin.utils`
- **THEN** those packages contain only the shared framework classes enumerated above
- **AND** no feature-specific logic (e.g. voting, session, grief, hub) resides there

#### Scenario: Gamemode packages unchanged
- **WHEN** inspecting `plugin.gamemode` and its subpackages
- **THEN** no classes are moved, renamed, or modified

### Requirement: Behavioral preservation

The refactor SHALL NOT change runtime behavior. Command names, descriptions, admin flags, parameters, event names, persistence keys, schedules, DI wiring, and logging SHALL remain identical.

The bootstrap SHALL remain valid: `plugin.json` SHALL still point to main class `plugin.Control`, and `Control` SHALL remain at the `plugin` package root. `Registry.init("plugin")` SHALL continue to discover and register all annotated classes in the (moved) package tree.

#### Scenario: Command registration preserved
- **WHEN** the server loads with the refactored classes
- **THEN** every `@ClientCommand` and `@ServerCommand` is registered with the same name, description, admin flag, and parameters as before the refactor
- **AND** the command-split classes register the same commands as the original aggregators

#### Scenario: Event and annotation scanning preserved
- **WHEN** the plugin initializes
- **THEN** `@Listener`, `@Schedule`, `@Init`, `@PlayerActionFilter`, `@Configuration`, `@Persistence`, and `@FileWatcher` classes are discovered and registered identically
- **AND** `SessionCreatedEvent`/`SessionRemovedEvent` subscribers are unaffected by the move to `plugin.session`

#### Scenario: Plugin boots with unchanged main class
- **WHEN** the plugin is loaded by Mindustry
- **THEN** `plugin.Control` is instantiated as the main plugin class
- **AND** the build compiles with `.\gradlew.bat :plugin:build --console=plain` exiting 0

### Requirement: No stale references to old packages

The refactored source SHALL contain no imports, reflection strings, or package declarations referencing the former package names `plugin.service`, `plugin.menus`, `plugin.commands.client`, `plugin.commands.server`, `plugin.type`, `plugin.event`, or `plugin.utils` (except for the retained shared classes that legitimately keep those packages).

#### Scenario: Grep of source tree
- **WHEN** the source is searched for `plugin.service.`, `plugin.menus.`, `plugin.commands.client.`, `plugin.commands.server.`, `plugin.type.`, `plugin.event.`, and `plugin.utils.` (as package references)
- **THEN** only the retained shared classes (`plugin.menus.PluginMenu`, `plugin.menus.PluginMenuService`, `plugin.event.PluginUnloadEvent`, `plugin.event.UnloadServerEvent`, `plugin.commands.ClientCommandHandler`, `plugin.commands.ServerCommandHandler`, `plugin.commands.ParamException`, `plugin.utils.Utils`, `plugin.utils.JsonUtils`, `plugin.utils.HttpUtils`, `plugin.utils.TimeUtils`, `plugin.utils.TextWidth`, `plugin.utils.CommandUtils`) reference their former packages
- **AND** no other references exist

### Requirement: Scope discipline

The refactor SHALL NOT change feature logic, fix bugs, remove dead code, or introduce new dependencies. Unrelated issues SHALL be documented only.

#### Scenario: No behavior changes in moved methods
- **WHEN** the moved service/menu/event/command classes are diffed against their originals
- **THEN** only the package declaration and imports differ
- **AND** method bodies, command metadata, and annotations are identical

#### Scenario: Dead code not removed
- **WHEN** the refactor is applied
- **THEN** `RankService` and `PlayerMetadata` are relocated with the rest of their feature, not deleted
- **AND** any noted defects are recorded in the change documentation rather than fixed

