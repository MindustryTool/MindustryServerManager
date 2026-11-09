# plugin-http-transport

## Purpose

TBD - created by syncing change replace-arc-http. Update Purpose after implementation.

## Requirements

### Requirement: Plugin HTTP uses a shared HttpClient
The plugin SHALL route general HTTP (via `HttpUtils` and `ServerImageService`) through a single shared `java.net.http.HttpClient` provided by `plugin.utils.HttpClients.shared()`. The shared client SHALL use a 10 second connect timeout, `followRedirects = NORMAL`, and the JDK default version. `PlayerConnectService` SSE SHALL use a dedicated client and `WsClient` (`:gateway`) SHALL keep its own client.

#### Scenario: Same instance reused
- **WHEN** `HttpUtils` and `ServerImageService` (default ctor) issue requests
- **THEN** both send over the identical instance returned by `HttpClients.shared()`

#### Scenario: Injectability preserved
- **WHEN** a test passes a stub `HttpClient` to `ServerImageService`'s injectable ctor
- **THEN** the stub handles requests and the shared client is not used

### Requirement: HttpUtils URL building preserved
`HttpUtils.get(Object... path)` and `HttpUtils.post(Object... path)` SHALL join segments with `/` exactly as before and build a JDK `HttpRequest` from the joined string, supporting full `https://` bases, path segments, and trailing query strings (for example `servers?page=1&size=20`).

#### Scenario: Joined GET with query
- **WHEN** `ApiGateway.getServers()` calls `HttpUtils.get(API_URL, query)`
- **THEN** the outgoing request resolves to the same URL the Arc implementation produced

#### Scenario: Joined POST with JSON body
- **WHEN** `Utils.generateMapPreview()` builds a POST from base plus segments and sets `Content-Type: application/json`
- **THEN** the JDK request carries the joined URL and the same body bytes

### Requirement: Error semantics preserved
`HttpUtils.send(HttpRequest, Duration)` and `HttpUtils.sendList(...)` SHALL throw `RuntimeException` carrying the request URL and response body on any `>=400` status, and SHALL wrap connect/read timeouts as `RuntimeException` indicating the timeout. Status codes below 400 SHALL return the response body as before.

#### Scenario: 4xx throws with body
- **WHEN** the server responds `404` with body `Not Found`
- **THEN** `send(...)` throws `RuntimeException` whose message contains the URL and `Not Found`

#### Scenario: Success returns body
- **WHEN** the server responds `200` with a JSON body
- **THEN** `send(...)` returns the body bytes and `sendList(...)` parses them as before

### Requirement: Timeout placement
Per-request timeouts SHALL be set on the JDK `HttpRequest` (`timeout(Duration)`) and SHALL remain 5 seconds for the server list and image fetch, 10 seconds by default, and 180 seconds for the map-preview upload. The shared client SHALL own only the 10 second connect timeout.

#### Scenario: Map upload keeps 180s
- **WHEN** `Utils.generateMapPreview()` sends the image POST
- **THEN** the request timeout is 180 seconds and completion is awaited on the `Tasks.io` thread

### Requirement: Map-preview upload runs blocking on Tasks.io
`Utils.generateMapPreview()` SHALL perform the image upload via blocking `HttpUtils.send(...)` inside `Tasks.io` (no `sendAsync` callback chain) and SHALL log failures from the resulting `RuntimeException` instead of branching on `HttpStatusException`.

#### Scenario: Upload failure logged
- **WHEN** the image POST fails with `>=400`
- **THEN** the thrown `RuntimeException` is logged and no write to the preview file occurs
