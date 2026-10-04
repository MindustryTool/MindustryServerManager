## 1. DTO Definitions

- [x] 1.1 Create `PluginQueryDto` in `dto` module with `owner`, `repo`, and `tag` fields
- [x] 1.2 Create `PluginVersionDto` in `dto` module with `updatedAt` field

## 2. Server-side Proxy & Cache

- [x] 2.1 Implement `PluginService` or cache service on server module with 5-minute Caffeine cache for versions and downloaded jar bytes
- [x] 2.2 Register `get-plugin-version` message handler in `GatewayService` to return `PluginVersionDto` via the cache service
- [x] 2.3 Register `download-plugin` message handler in `GatewayService` to return the jar byte array via the cache service
- [x] 2.4 Verify Javalin/Jetty WebSocket payload and text frame size limits support multi-megabyte payloads

## 3. Plugin-side Delegation & Schedule Update

- [x] 3.1 Update `PluginData.getPluginVersion()` to send a `get-plugin-version` WebSocket request via `ApiGateway`
- [x] 3.2 Update `PluginData.download()` to send a `download-plugin` WebSocket request via `ApiGateway`
- [x] 3.3 Ensure network or WebSocket disconnections are handled gracefully in `PluginData` without falling back to direct HTTP
- [x] 3.4 Update `PluginUpdater.checkUpdate` schedule annotation fixed delay from 5 to 1 minute

## 4. Verification & Testing

- [x] 4.1 Add unit tests for server-side plugin cache service (cache hit, cache miss, expiration)
- [x] 4.2 Test serialization of `PluginQueryDto` and `PluginVersionDto` across the gateway
- [x] 4.3 Verify full build passes with `./gradlew build`
