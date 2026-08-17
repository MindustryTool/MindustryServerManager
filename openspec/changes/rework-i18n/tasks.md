## 1. Translation core

- [x] 1.1 Create a pure-Java `plugin.utils.TrCatalog` (arc/Mindustry-free) that parses JSON catalog strings into flattened dotted keys, validates key segments against `[a-z0-9_]+`, warns on violations, resolves via the fallback chain (language tag → base language → `en` → raw key), and interpolates named `{placeholder}` tokens
- [x] 1.2 Create `plugin.utils.Tr` facade with `t(Locale, key, Object... args)`, `t(Session, key, ...)`, `t(Player, key, ...)`, and `tWithFallback(Locale, key, Object fallbackText, Object... args)` overloads; alternating name/value varargs; delegates to `TrCatalog` (the fallback overload is named `tWithFallback` — a `t(Locale, String, Object, Object...)` overload is ambiguous with `t(Locale, String, Object...)` at 3+ arg call sites)
- [x] 1.3 Create a catalog loader `@Component` whose `@Init` reads every `i18n/<lang>.json` via arc's file API and hands the raw strings to `TrCatalog` for parsing/storage; missing file logs a warning
- [x] 1.4 Create `plugin/src/main/resources/i18n/en.json` with module namespaces and migrate text as call sites are converted

## 2. Migrate hub

- [x] 2.1 Migrate `hub/GlobalServerListMenu.java` (19 `I18n.t` sites) to `Tr.t`
- [x] 2.2 Migrate `hub/ServerListMenu.java` (10 sites) to `Tr.t`
- [x] 2.3 Migrate `hub/ServerRedirectMenu.java` (8 sites) to `Tr.t`
- [x] 2.4 Migrate `hub/ServerUtils.java` (4 sites) to `Tr.t`
- [x] 2.5 Migrate `hub/HubCommands.java` (1 site) to `Tr.t`

## 3. Migrate vote

- [x] 3.1 Migrate `vote/VoteNewWaveService.java` (7 sites) to `Tr.t`
- [x] 3.2 Migrate `vote/RtvService.java` (8 sites) to `Tr.t`
- [x] 3.3 Migrate `vote/RtvMenu.java` (6 sites) to `Tr.t`
- [x] 3.4 Migrate `vote/VoteCommands.java` (1 site) to `Tr.t`

## 4. Migrate session

- [x] 4.1 Migrate `session/SessionCommands.java` (4 sites) to `Tr.t`
- [x] 4.2 Migrate `session/PlayerInfoMenu.java` (2 sites) to `Tr.t`
- [x] 4.3 Migrate `session/SessionUtils.java` (2 sites) to `Tr.t` (level-up message and kill message use named placeholders)
- [x] 4.4 Migrate `session/SessionService.java` (1 site) and `session/RankUtils.java` (1 site) to `Tr.t`
- [x] 4.5 Migrate `session/DailyService.java` (1 site, daily bonus message) to `Tr.t`

## 5. Migrate grief

- [x] 5.1 Migrate `grief/AdminService.java` (9 sites) to `Tr.t`
- [x] 5.2 Migrate `grief/TileLogger.java` (5 sites) to `Tr.t`
- [x] 5.3 Migrate `grief/GriefMenu.java` (4 sites) to `Tr.t`
- [x] 5.4 Migrate `grief/GreifLoginMenu.java` (2 sites) to `Tr.t`
- [x] 5.5 Migrate `grief/GriefDetectService.java` (1 site) to `Tr.t`

## 6. Migrate tip, maprating, trail

- [x] 6.1 Migrate `tip/TipService.java` (16 sites) to `Tr.t`; tips use module namespace `tip.*`
- [x] 6.2 Migrate `maprating/RateMapMenu.java` (2 sites) and `maprating/MapRatingCommands.java` (2 sites) to `Tr.t`
- [x] 6.3 Migrate `trail/TrailMenu.java` (4 sites) to `Tr.t`; keep dynamic `req.getMessage()` text as-is (already-localized literals)

## 7. Migrate admin, security, commands, event

- [x] 7.1 Migrate `admin/ServerCommands.java` (1 site) to `Tr.t`
- [x] 7.2 Migrate `security/SecurityService.java` (1 site) to `Tr.t`
- [x] 7.3 Migrate `commands/ClientCommandHandler.java` (4 sites) to `Tr.t`
- [x] 7.4 Migrate `event/EventHandler.java` (3 sites) to `Tr.t`

## 8. Migrate gamemode/catali

- [x] 8.1 Migrate `gamemode/catali/CataliGamemode.java` (~18 sites) to `Tr.t`
- [x] 8.2 Migrate `gamemode/catali/CataliTip.java` (3 sites) to `Tr.t`
- [x] 8.3 Migrate `gamemode/catali/command/client/CataliClientCommands.java` (7 sites) to `Tr.t`
- [x] 8.4 Migrate `gamemode/catali/menu/RareUpgradeMenu.java`, `RareUpgradeTierSelectUnitMenu.java`, `RareUpgradeTierSelectUpgradeMenu.java`, `RareUpgradeBuffSelectBuffMenu.java`, `RareUpgradeBuffSelectUnitMenu.java` to `Tr.t`
- [x] 8.5 Migrate `gamemode/catali/menu/CommonUpgradeMenu.java`, `CommonUpgradeQuantityMenu.java`, `AssignNextLeaderMenu.java`, `AbandonMenu.java`, `JoinTeamMenu.java`, `IncomingRequestMenu.java`, `AIPickMenu.java` to `Tr.t`
- [x] 8.6 Add the `catali.*` namespace to `en.json`; grep `plugin/src` for `I18n` and confirm only `I18n.java` remains

## 9. Config-driven messages

- [x] 9.1 Migrate `Cfg.WELCOME_MESSAGE` usage to `Tr.t(session.locale, "welcome.message", Cfg.WELCOME_MESSAGE)`
- [x] 9.2 Migrate `Cfg.CHOOSE_SERVER_MESSAGE` usage to `Tr.t(session.locale, "hub.choose_server", Cfg.CHOOSE_SERVER_MESSAGE)`

## 10. Remove I18n and verify

- [x] 10.1 Delete `plugin/utils/I18n.java`; compile and fix any remaining references
- [x] 10.2 Grep `plugin/src` for `I18n` and confirm zero references remain
- [x] 10.3 Build the plugin (gradle) and confirm the jar includes `i18n/*.json` and compiles cleanly
- [x] 10.4 Spot-check a few messages render with colors, placeholders, and correct fallback

## 11. Second locale demonstration

- [x] 11.1 Add `plugin/src/main/resources/i18n/vi.json` translating a meaningful subset of keys (e.g. hub, vote, welcome)
- [x] 11.2 Verify: `vi` player sees Vietnamese text, `en-US` falls back to `en`, unknown locale falls back to `en`, missing key returns the raw key

## 12. Unit tests

- [x] 12.1 Enable JUnit 5 dependencies in `plugin/build.gradle` (uncomment the `testImplementation`/`testRuntimeOnly` jupiter lines) and add a `test` source set
- [x] 12.2 Write `plugin/src/test/java/plugin/utils/TrCatalogTest.java` covering: key-format validation (invalid segments skipped, valid keys registered), fallback chain (`en-US` → `en`, unknown locale → `en` → raw key), named placeholder interpolation with color-code preservation, and missing-key returns raw key
- [x] 12.3 Write config-fallback tests for the `tWithFallback` overload: absent key everywhere returns interpolated fallback text; present key returns the catalog value
- [x] 12.4 Write a catalog-load test that parses an `en.json` fixture via `TrCatalog` and asserts nested values resolve by dotted key (also loads `vi.json` and asserts per-locale override, region-tag and unknown-locale fallback, and en fallback for keys absent in `vi`)
- [x] 12.5 Run `./gradlew :plugin:test` locally and confirm all tests pass

## 13. GitHub Actions

- [x] 13.1 Update `.github/workflows/build-plugin.yml`: uncomment/enable the `Run tests` step (`./gradlew :plugin:test --stacktrace`) after the build step
- [x] 13.2 Enable the test-report upload artifact step (runs with `if: always()` so reports upload even on failure)
- [ ] 13.3 Verify the workflow path triggers still cover `plugin/**` (tests run on every relevant push to `main`)
