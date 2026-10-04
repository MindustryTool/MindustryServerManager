## Context

Currently, the server codebase creates standalone `java.net.http.HttpClient` instances across at least 8 different locations. In Java 11+, every `HttpClient` instance spins up its own `HttpClient-X-SelectorManager` background thread and internal socket channels. In `GoogleWebProvider`, a new `HttpClient` was even being created inside a retry loop for every proxied request attempt.

This leads to thread bloating, potential socket exhaustion, GC pressure, and defeats HTTP/1.1 and HTTP/2 connection reuse (`Keep-Alive`).

## Goals / Non-Goals

**Goals:**
- Provide a centralized `server.utils.HttpClients` class with a shared, long-lived `HttpClient` (`HttpClients.shared()`) and a factory for long-lived proxied clients (`HttpClients.createProxied(...)`).
- Implement dynamic `ProxySelector` in `MultiSourceProxyPool` via `asProxySelector()`, handling candidate selection and automatic dead proxy eviction via `connectFailed(...)`.
- Update `GoogleWebProvider` to use exactly one long-lived `HttpClient` (direct or proxied), eliminating all per-request client allocations.
- Replace ad-hoc `HttpClient.newBuilder()` calls in `ApiService`, `GatewayService`, `LingvaProvider`, and `ProxySource` implementations with `HttpClients.shared()`.

**Non-Goals:**
- Replacing Java's `java.net.http.HttpClient` with an external library (like Apache HttpClient or OkHttp).
- Modifying Docker client HTTP configuration (which uses `ApacheDockerHttpClient` for Unix/Windows pipe sockets).

## Decisions

### Decision 1: Centralized `HttpClients` Utility
- **Design**:
  - `HttpClients` manages a lazily-initialized shared singleton `HttpClient`:
    ```java
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    ```
  - Exposes `HttpClients.shared()` for general outbound HTTP calls.
  - Exposes `HttpClients.createProxied(ProxySelector, Duration)` to construct a long-lived proxied client.

### Decision 2: Dynamic `ProxySelector` in `MultiSourceProxyPool`
- **Design**:
  - `MultiSourceProxyPool` provides `asProxySelector()` extending `java.net.ProxySelector`.
  - `select(URI)`: Calls `getNextCandidate()` and returns `List.of(new Proxy(Proxy.Type.HTTP, candidate))`.
  - `connectFailed(URI, SocketAddress, IOException)`: If `SocketAddress` is an `InetSocketAddress`, automatically invokes `evict(inet)`.

### Decision 3: Long-Lived Proxied Client in `GoogleWebProvider`
- **Design**:
  - `GoogleWebProvider` initializes its `HttpClient` once in the constructor.
  - If `proxyPool != null`, it builds a single proxied client: `HttpClients.createProxied(proxyPool.asProxySelector(), PROXY_CONNECT_TIMEOUT)`.
  - In `executeProxiedRequest(...)`, it uses this single client for all attempts.

## Risks / Trade-offs

- **[Risk] Shared Client Timeout/Thread Blocking**: Multiple components sharing one client.
  - **Mitigation**: Java `HttpClient` is fully asynchronous and non-blocking internally. Individual requests specify their own request timeouts (`timeout(Duration)`), which ensures independent SLA per request.
