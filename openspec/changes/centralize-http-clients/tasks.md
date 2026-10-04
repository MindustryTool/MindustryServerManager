## 1. Centralized HttpClients Utility

- [x] 1.1 Implement `server.utils.HttpClients` with shared singleton client and proxied client factory
- [x] 1.2 Write unit tests in `HttpClientsTest` verifying client configuration and singleton reuse

## 2. Dynamic ProxySelector in MultiSourceProxyPool

- [x] 2.1 Implement `asProxySelector()` in `MultiSourceProxyPool` returning a `ProxySelector` that rotates candidate proxies and evicts on `connectFailed`
- [x] 2.2 Add unit tests verifying `asProxySelector()` returns candidate proxies and handles connection failures

## 3. Long-Lived Client in GoogleWebProvider

- [x] 3.1 Refactor `GoogleWebProvider` to instantiate a single long-lived `HttpClient` for direct mode or proxied mode (via `HttpClients.createProxied`)
- [x] 3.2 Remove `HttpClient.newBuilder()...build()` from inside `executeProxiedRequest(...)` retry loop
- [x] 3.3 Verify existing `GoogleWebProviderTest` suite passes

## 4. Server-Wide HttpClient Consolidation

- [x] 4.1 Update `ApiService` to use `HttpClients.shared()`
- [x] 4.2 Update `GatewayService.GatewayClient.Backend` to use `HttpClients.shared()`
- [x] 4.3 Update `LingvaProvider` to default to `HttpClients.shared()`
- [x] 4.4 Update `ProxyScrapeSource`, `GeonodeSource`, and `HProxySource` to default to `HttpClients.shared()`

## 5. Verification

- [x] 5.1 Run `./gradlew :server:test` and verify all tests pass
