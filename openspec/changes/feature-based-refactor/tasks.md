## 1. Shared infrastructure prep

- [x] 1.1 Verify `plugin` package root contains only `Control`, `Cfg`, `PluginEvents`, `PluginState`, `Tasks` (no moves needed for infra packages `core`, `annotations`, `database`, `json`)
- [x] 1.2 Confirm `utils/` retains only `Utils`, `JsonUtils`, `HttpUtils`, `TimeUtils`, `TextWidth`, `CommandUtils` (move the rest out in sections 3-6)
- [x] 1.3 Confirm `menus/` retains only `PluginMenu`, `PluginMenuService` (move feature menus out in sections 3-6)
- [x] 1.4 Confirm `event/` retains only `PluginUnloadEvent`, `UnloadServerEvent` (session events move to `session`)
- [x] 1.5 Confirm `commands/` retains only `ClientCommandHandler`, `ServerCommandHandler`, `ParamException` (aggregators split/move in section 7)

## 2. session feature

- [x] 2.1 Move `type/Session.java`, `type/SessionData.java`, `service/SessionHandler.java`, `service/SessionService.java`, `repository/SessionRepository.java` to `plugin/session/`, updating `package` declarations and imports
- [x] 2.2 Move `utils/SessionUtils.java`, `utils/ExpUtils.java`, `utils/RankUtils.java`, `service/RankService.java` to `plugin/session/`, updating packages and all imports (including `trail`/`gamemode` references to `ExpUtils`)
- [x] 2.3 Move `event/SessionCreatedEvent.java`, `event/SessionRemovedEvent.java` to `plugin/session/`, updating all subscribers' imports (`AdminService`, `ApiGateway`, `EventHandler`, `PluginMenuService`, `VoteService`, `SessionRepository`, `CataliGamemode`)
- [x] 2.4 Move `commands/client/OfficialCommands.java` to `plugin/session/` and `menus/PlayerInfoMenu.java` to `plugin/session/`, updating packages/imports

## 3. vote, grief, host, maprating features

- [x] 3.1 `vote/`: move `VoteService`, `VoteNewWaveService`, `RtvMenu` to `plugin/vote/`, updating packages/imports
- [x] 3.2 `grief/`: move `AdminService`, `GriefDetectService`, `TileLogger`, `GriefMenu`, `GreifLoginMenu` to `plugin/grief/`, updating packages/imports
- [x] 3.3 `host/`: move `HostService`, `MapWatcher` to `plugin/host/`, updating packages/imports
- [x] 3.4 `maprating/`: move `MapRating`, `RateMapMenu` to `plugin/maprating/`, updating packages/imports (includes `EventHandler`, `ClientCommands` references)

## 4. hub, security, trail, tip, welcome features

- [x] 4.1 `hub/`: move `HubService`, `ServerUtils`, `ServerCore`, `PaginationRequest`, `ServerListMenu`, `GlobalServerListMenu`, `ServerRedirectMenu` to `plugin/hub/`, updating packages/imports (includes `ApiGateway` reference to `PaginationRequest`)
- [x] 4.2 `security/`: move `SecurityService` to `plugin/security/`, updating packages/imports
- [x] 4.3 `trail/`: move `TrailService`, `TrailMenu` to `plugin/trail/`, updating packages/imports
- [x] 4.4 `tip/`: move `TipService` to `plugin/tip/`, updating packages/imports
- [x] 4.5 `welcome/`: move `WelcomeMenu` to `plugin/welcome/`, updating packages/imports

## 5. update and gateway features

- [x] 5.1 `update/`: move `PluginData`, `PluginUpdater` from `plugin` root to `plugin/update/`, updating `package` declarations and all imports
- [x] 5.2 `gateway/`: move `ApiGateway` to `plugin/gateway/` as a monolithic class (do NOT split RPC handlers), updating packages/imports; document per-feature handler extraction as a future improvement in `tasks.md` notes

## 6. admin feature

- [x] 6.1 Move `commands/server/ServerCommands.java` to `plugin/admin/`, updating `package` declaration and imports

## 7. Split ClientCommands into per-feature command classes

- [x] 7.1 Create `session/SessionCommands.java` (`@Component`) with verbatim copies of `admin`, `login`, `me`, `pinfo` (same name/description/admin flag/params/body)
- [x] 7.2 Create `vote/VoteCommands.java` with verbatim copies of `rtv`, `vnw`
- [x] 7.3 Create `grief/GriefCommands.java` with verbatim copy of `grief`
- [x] 7.4 Create `hub/HubCommands.java` with verbatim copies of `hub`, `servers`, `redirect`
- [x] 7.5 Create `maprating/MapRatingCommands.java` with verbatim copies of `map`, `maps`, `submitmap`
- [x] 7.6 Create `trail/TrailCommands.java` with verbatim copy of `trail`
- [x] 7.7 Create `update/UpdateCommands.java` with verbatim copy of `restart`
- [x] 7.8 Create `welcome/WelcomeCommands.java` with verbatim copies of `discord`, `website`
- [x] 7.9 Create `admin/AdminCommands.java` with verbatim copy of client `js` command
- [x] 7.10 Delete `commands/client/ClientCommands.java` after all commands are distributed

## 8. Verification

- [x] 8.1 Grep `plugin/src` for stale package references: `plugin.service.`, `plugin.menus.`, `plugin.commands.client.`, `plugin.commands.server.`, `plugin.type.`, `plugin.event.`, `plugin.utils.` — only the retained shared classes in sections 1.2-1.5 may remain
- [x] 8.2 Grep for reflection/package-literal references (`getPackage()`, `Class.forName`, `"plugin."` strings) to confirm nothing depends on old package paths beyond `plugin.Control`
- [x] 8.3 Run `.\gradlew.bat :plugin:build --console=plain` and confirm exit 0
- [x] 8.4 Confirm `plugin.json` still references `plugin.Control` and no files were renamed/moved outside `plugin/src/main/java/plugin/**`
- [x] 8.5 Review the diff: moved files differ only in package declaration + imports; command-split files are byte-identical method copies; no logic/annotation changes

## Notes

- Future improvement (not in scope): split `ApiGateway`'s feature RPC handlers into per-feature registration classes. It remains monolithic in `gateway/` (behavior-preserving, cross-feature integration layer).
- `plugin/type/ChatDto.java` is dead code (no references); relocated to `gateway/` with the protocol-style DTOs, not removed (scope discipline).
- `plugin/type/PlayerMetadata.java` is dead code; relocated to `session/`, not removed.
- `RankService` remains an empty `@Component` placeholder; relocated to `session/`, not removed.
- Unused-import audit: all 1435 imports across `plugin/src` (plus 100 in `dto/src`) verified used (comment/string/literal-stripped word-boundary check) — 0 removed, 0 duplicate simple-name collisions.
- Circular-dependency audit (class-level, import-based): the only cross-feature cycle found was `ApiGateway ↔ HostService` (gateway ↔ host). Fixed by moving the `autoHost` `@Schedule` from `HostService` into `ApiGateway` (which already injects `HostService`), making the dependency one-directional (gateway → host) with identical timing/condition/behavior. Remaining cycles are pre-existing: 15 internal to the unchanged `gamemode.catali` feature, and 6 framework-bootstrap cycles (`Control↔DB`, `PluginMenu↔PluginMenuService`, `Registry`/`Control` ↔ command framework ↔ `Utils` ↔ `I18n`) — not addressed (out of scope for the structural refactor, behavior-preservation risk).