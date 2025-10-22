# MindustryServerManager v2 Migration Plan

This plan outlines the steps to migrate to v2 while preserving full feature parity with the existing manager. It documents scope, parity checklist (copied from the general plan), proposed v2 improvements, execution steps, testing, rollout, and acceptance criteria.

## Objectives

- Achieve complete feature parity with current manager capabilities.
- Improve reliability, security, and observability.
- Reduce operational toil and simplify future extension.

## Scope

- In scope: All public APIs, internal APIs, services, filters, security, error handling, configuration, Docker integration, and utilities as enumerated below.
- Out of scope: Major UI rewrite, non-manager features, external ecosystem changes beyond documented integrations (Discord webhook, Docker eventing).

## Change Requests (v2)

- Remove all internal API
  - Decommission `/internal-api/**` routes.
  - Remove `ServerApiController` and `ServerFilter` context binding.
  - Eliminate backend methods in `GatewayService` used only by internal API.
  - Adjust `SecurityFilter` to drop internal IP checks tied to internal API.
  - Migrate necessary flows to secure public APIs or alternative plugin → manager communications.
- Refactor all codes
  - Establish clear module boundaries (controllers, services, gateway, docker, utils).
  - Normalize package structure and naming conventions across files.
  - Remove dead code and consolidate duplicate logic.
  - Standardize DTOs, validation, and error envelopes.
  - Unify timeout/retry policies and resilience patterns.
  - Improve tests: unit, integration, and E2E around hosting/commands/files/stats.
- Rename routes
  - Introduce a consistent `/v2` base for all public APIs.
  - Align resource naming and verbs to REST conventions.
  - Provide a compatibility map (old → new) and deprecation schedule.
  - Example proposals:
    - `/api/v1/servers` → `/v2/servers`
    - `/api/v1/servers/{id}/files` → `/v2/servers/{id}/files`
    - `/api/v1/servers/{id}/live-stats` → `/v2/servers/{id}/stats/live`
  - Implement HTTP redirects or client-side updates as needed.

## Feature Parity Checklist (Copied)

Ensure the following capabilities exist in v2 exactly as documented.

### Public API (`/api/v1`)
- `GET /servers` — list all servers with stats.
- `GET /servers/{id}` — get server details with stats.
- `GET /servers/{id}/players` — get live players.
- `GET /servers/{id}/files?path=...` — list/read files or directories (5 MB limit).
- `GET /servers/{id}/files/exists?path=...` — existence check.
- `GET /servers/{id}/files/download?path=...` — download binary.
- `POST /servers/{id}/files` (multipart) — create file.
- `DELETE /servers/{id}/files?path=...` — delete files/directories recursively.
- `GET /servers/{id}/commands` — get command stream/poll.
- `POST /servers/{id}/commands` — send console command.
- `POST /servers/{id}/say` — send chat message.
- `POST /servers/{id}/host` (SSE) — host server from `HostServerRequest`.
- `POST /servers/{id}/host-from-server` (SSE) — host from existing server config.
- `POST /servers/{id}/set-player` — set/update player info.
- `GET /servers/{id}/stats` — aggregated stats.
- `GET /servers/{id}/live-stats` (SSE) — live stats stream.
- `PUT /servers/{id}/shutdown` — graceful shutdown.
- `DELETE /servers/{id}/remove` — remove container.
- `POST /servers/{id}/pause` — pause server.
- `GET /servers/{id}/image` — PNG image snapshot.
- `GET /servers/{id}/ok` — readiness/ok state.
- `PUT /servers/{id}/config` (multipart `key`, `value`) — set server config.
- `GET /servers/{id}/mods` — list mods.
- Additional content endpoints (maps, plugin version, manager version, server JSON/routes, mismatch handling, manager mods) maintained as-is.

### Internal API (`/internal-api/v1`)
- `POST /players` — set player and trigger stats sync.
- `POST /players/leave` — player leave; stats sync.
- `POST /build-log` — send build logs.
- `GET /total-player` — total player count.
- `POST /chat` — broadcast chat.
- `POST /console` — send console (temporary; keep behavior until deprecation phase).
- `POST /host` — host by server id (text/plain).
- `GET /servers?page&size` — paginated server list.
- `POST /translate/{targetLanguage}` — translate text.

### Services
- ServerService
  - Docker integration (labels, event watching, logs/stats attach).
  - Discord webhook notifications for container events.
  - Read container `ServerContainerMetadata` from labels.
  - File operations with size constraints via FileService.
  - Player and chat operations.
  - Hosting flows (`host`, `hostFromServer`) using SSE.
  - Config updates (`setConfig`).
  - Gateway interactions for `json`, `stats`, `image`, `commands`, `hosting`.
- GatewayService
  - Per-server `GatewayClient` containing:
    - Server client base URL: `http://localhost:9999/` in dev, `http://{serverId}:9999/` in prod.
    - Endpoints: `json`, `plugin-version`, `set-player`, `pause`, `players`, `ok`, `stats`, `image`, `commands` (get/post), `say`, `host`, `hosting`, `player-infos`.
    - Error mapping: 4xx to `ApiError`, timeouts with descriptive `ApiError`, controlled retries.
  - Backend interactions used by internal API (set player/stats, build logs, chat, host, server pagination, translate).
- DockerService
  - Inspect manager image (`ghcr.io/mindustrytool/mindustry-server-manager`).
- FileService
  - Safe path validation (block `..`, `./`).
  - `getFiles` returns `ServerFileDto` with name/size/dir and file contents when file.
  - `addFile` writes multipart content.
  - `deleteFile` recursively.
  - Max file size `5 MB`.

### Filters & Security
- SecurityConfig
  - CORS for `http://localhost:3000` and `https://mindustry-tool.com`, headers `*`, methods `*`, credentials allowed, `maxAge=1 day`.
  - CSRF disabled, form login/basic disabled.
- SecurityFilter (JWT)
  - Bypass `/`.
  - Internal IP restriction for `/internal-api` (`172.*`, `10.*`, `192.168.*`, `127.0.0.1`).
  - Validate `Authorization: Bearer` JWT using HMAC256, issuer `MindustryTool`, secret from `app.server-config.security-key`.
  - Parse subject JSON to `ServerManagerJwt` and attach to Reactor Context.
- ServerFilter
  - Require `X-SERVER-ID` for `/internal-api`, resolve `GatewayClient`, attach to Reactor Context.
- RequestFilter
  - Log requests >200ms: duration, status, method, URL.

### Error Handling
- GlobalExceptionHandler
  - Mapped statuses and detailed validation errors.
  - Include URL and IP metadata when available.
  - Handle `ApiError` uniformly.

### Configuration
- EnvConfig (`app.*`)
  - Docker: `mindustryServerImage`, `serverDataFolder`, `authToken`, `username`.
  - ServerConfig: `autoPortAssign`, `accessToken`, `securityKey`, `dataFolder`, `serverUrl`.
- Config
  - `ENV`, `IS_DEVELOPMENT`, `IS_PRODUCTION`.
  - Ports `DEFAULT_MINDUSTRY_SERVER_PORT=6567`, `MAXIMUM=20000`.
  - Volume folder: dev `./data`, prod `SERVER_MANAGER_DATA`.
  - Labels: `com.mindustry-tool.server`, `com.mindustry-tool.server.id`.
  - `discordWebhook` URL.
  - Beans: `ModelMapper`, tuned `ObjectMapper`, `WebClient` (follow redirects), server codec `16MB`.

### Types & Utilities
- Types: data (`Player`, `Team`, `ServerContainerMetadata`, `ServerManagerJwt`), requests (`AddServerFileRequest`, `InitServerRequest`, `HostServerRequest`, `HostFromSeverRequest`, `PaginationRequest`, `ServerPlan`), responses (`ApiServerDto`, `BuildLogDto`, `BuildingDto`, `CommandParamDto`, `ErrorResponse`, `LiveStats`, `ManagerMapDto`, `ManagerModDto`, `MapDto`, `MindustryPlayerDto`, `ModDto`, `ModMetaDto`, `PlayerDto`, `PlayerInfoDto`, `ServerCommandDto`, `ServerDto`, `ServerFileDto`, `ServerWithStatsDto`, `StatsDto`).
- Utilities: `ApiError`, `SSE`, `ServerUtils`, `Utils`.

### Build & Dependencies
- Spring Boot 3.3.x: WebFlux, Security, Validation, Reactor Netty.
- Docker Java core + HTTP client 5.
- Jackson + JavaTimeModule.
- ModelMapper.
- Auth0 java-jwt.
- Arc + Mindustry core (v146).
- Caffeine (available).
- DevTools.
- Exclusions: servlet stack and commons-logging.

### Deployment & Operations
- Manager port `8088`.
- Server plugin `9999` HTTP endpoints.
- Discord webhook notifications on Docker events.
- Data directories: dev `./data`, prod via `SERVER_MANAGER_DATA`.

## Proposed v2 Improvements

- Config Hardening
  - Move CORS origins to properties; add allow-list for internal IP ranges.
  - Explicit WebClient timeouts and connection pool tuning.
- Security & Auth
  - Token rotation support; optional audience claim enforcement.
  - Structured 401/403 responses with error codes.
- Reliability
  - Backoff/retry policies standardized across gateway calls.
  - Circuit breaker (resilience4j) for plugin endpoints.
- Observability
  - Structured logs (JSON) and correlation IDs.
  - Metrics: HTTP, Docker events, gateway success/failure; export to Prometheus.
- API Consistency
  - Pagination request/response standardization.
  - Consistent SSE event format and heartbeat.
  - Error envelope normalization (`code`, `message`, `details`).
- Ops Tooling
  - Health endpoints: liveness/readiness with backing checks.
  - Admin endpoints to list gateway cache, clear stale entries.

## Migration Steps

1. Baseline
   - Create v2 branch, scaffold project structure mirroring current.
   - Port `Config`, `EnvConfig`, `SecurityConfig`, filters, `GlobalExceptionHandler`.
2. Services
   - Port `GatewayService` with status handlers, timeouts, retries; add standardized client builder.
   - Port `ServerService`: Docker events, logs/stats attach, Discord notifications, SSE streams, file ops, hosting, config set.
   - Port `DockerService`, `FileService` (same safety and size constraints).
3. Controllers
   - Port `APIController`, `ServerController`, `ServerApiController` with identical routes and payloads.
4. Types & Utilities
   - Port all DTOs and data types; ensure `ObjectMapper` compatibility.
   - Port utilities (`ApiError`, `SSE`, `Utils`, `ServerUtils`).
5. Configuration
   - Map properties via `EnvConfig`; add missing defaults and docs.
6. Improvements Layer
   - Add resilience/observability enhancements (non-breaking).
   - Standardize pagination and SSE envelopes.
7. Tests
   - Unit: filters, handlers, utilities.
   - Integration: controller routes, error handling, security.
   - E2E (mock plugin): gateway interactions, hosting flow.
   - Load: SSE streams and command endpoints.
8. Compatibility Checks
   - Validate internal API with existing plugin.
   - Verify external clients (origins, tokens).
9. Documentation
   - Update general.md and publish v2 changes.
10. Rollout
   - Canary deploy, monitor errors/latency, rollback hooks.

## Backward Compatibility

- Preserve route paths, query params, payload shapes, and response schemas.
- Maintain JWT issuer and secret expectations.
- Internal API IP restrictions unchanged.
- Keep temporary `/internal-api/v1/console` until plugin deprecation window ends.

## Testing Plan

- Contract tests for each endpoint (status codes, payloads).
- Security tests for unauthorized and forbidden flows.
- Gateway timeout/retry behavior tests.
- Docker event simulation for webhook and attach flows.
- SSE stability tests (heartbeat, reconnect).

## Risks & Mitigations

- Plugin incompatibility — mitigate via staging environment and contract tests.
- Docker API changes — pin client versions; add smoke checks.
- Performance regressions — load test and profile before release.
- Configuration drift — centralize configs and document env requirements.

## Timeline (Indicative)

- Week 1–2: Baseline port, core services, filters.
- Week 3: Controllers, types, utilities, tests baseline.
- Week 4: Improvements (resilience, observability), load testing.
- Week 5: Canary rollout, fix issues, finalize docs.

## Acceptance Criteria

- All parity endpoints and features pass contract tests.
- No breaking changes observed by plugin and clients.
- Metrics, logs, and health endpoints functional.
- Documentation updated and validated.

## References

- `plans/general.md` — authoritative feature list and behavior descriptions.
- Code modules: `Config`, `EnvConfig`, filters, services, controllers, types, utilities.