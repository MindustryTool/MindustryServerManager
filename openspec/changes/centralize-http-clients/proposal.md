## Why

Throughout the server module, `HttpClient` instances are created in an ad-hoc manner across services, sources, and providers (e.g. `ApiService`, `GatewayService.GatewayClient`, `LingvaProvider`, `ProxyScrapeSource`, `GeonodeSource`, `HProxySource`, and dynamically per request in `GoogleWebProvider`). 

Each Java `HttpClient` allocates dedicated `SelectorManager` background threads, socket channels, and SSL engines. Creating new clients per request or per client connection leads to resource leaks, thread bloating, GC pressure, and prevents HTTP connection keep-alive reuse. Centralizing HTTP client management eliminates these resource leaks and optimizes server performance.

## What Changes

- Introduce `server.utils.HttpClients` static utility:
  - `HttpClients.shared()`: Long-lived shared `HttpClient` configured with standard 10s connect timeout, redirect handling, and shared worker executor.
  - `HttpClients.createProxied(ProxySelector, Duration)`: Factory for long-lived proxied `HttpClient` instances.
- Implement dynamic `ProxySelector` in `MultiSourceProxyPool` via `proxyPool.asProxySelector()`, which automatically selects candidate proxies from the pool and handles eviction via `connectFailed(...)`.
- Refactor `GoogleWebProvider` to use a single, long-lived `HttpClient` (either `HttpClients.shared()` for direct mode, or a single proxied client backed by `proxyPool.asProxySelector()` for proxied mode), completely eliminating client creation inside request loops.
- Refactor `ApiService`, `GatewayService`, `LingvaProvider`, `ProxyScrapeSource`, `GeonodeSource`, and `HProxySource` to use `HttpClients.shared()`.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `chat-translation`: Specify that HTTP requests for translation and proxy scraping reuse shared/long-lived HTTP client instances without allocating per-request client resources.

## Impact

- `server/src/main/java/server/utils/HttpClients.java`: New utility class.
- `server/src/main/java/server/service/MultiSourceProxyPool.java`: Expose `asProxySelector()`.
- `server/src/main/java/server/service/GoogleWebProvider.java`: Single long-lived proxied `HttpClient`.
- `server/src/main/java/server/service/ApiService.java`: Use `HttpClients.shared()`.
- `server/src/main/java/server/service/GatewayService.java`: Use `HttpClients.shared()`.
- `server/src/main/java/server/service/LingvaProvider.java`: Use `HttpClients.shared()`.
- `server/src/main/java/server/service/proxy/`: Use `HttpClients.shared()`.
