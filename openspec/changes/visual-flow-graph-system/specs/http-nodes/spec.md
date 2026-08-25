## ADDED Requirements

### Requirement: Four generic HTTP nodes
The system SHALL provide Network GET, POST, PUT, DELETE as four async node types taking url, headers map, query map, and body where applicable, returning a common `HttpResponse {status, headers, body, success}`; no node type per endpoint API.

#### Scenario: POST with body returns response
- **WHEN** a graph executes Network POST against a test endpoint with JSON body
- **THEN** the Await continuation receives an HttpResponse whose status/body/success reflect the server reply

### Requirement: Never block the main thread
HTTP execution SHALL occur entirely on IO executors; requests MUST NOT be started synchronously on the main thread and continuations MUST resume there only after completion.

#### Scenario: Slow endpoint does not lag ticks
- **WHEN** the target endpoint delays 3 seconds before responding
- **THEN** server ticks continue uninterrupted while the request is in flight

### Requirement: Limits and policies
HTTP nodes SHALL enforce configurable timeout, maximum response size (enforced by streaming abort), maximum request size, redirect policy defaulting to NEVER, TLS via the system trust store, connection pooling, and per-graph plus global rate limits; exceeding limits yields typed errors catchable by Try nodes.

#### Scenario: Oversized response aborted
- **WHEN** a response exceeds maxResponseBytes mid-stream
- **THEN** the transfer aborts and the execution receives a size-limit error rather than exhausting memory

#### Scenario: Rate limit throttles
- **WHEN** a loop fires 100 HTTP calls above the per-graph rate
- **THEN** excess calls receive rate-limit errors or queue per policy instead of flooding the target

### Requirement: Cancellation propagation
Cancelling an execution awaiting an HTTP response SHALL cancel the underlying request and free its resources.

#### Scenario: Mid-flight cancel aborts request
- **WHEN** an execution is cancelled while its Network GET waits on a slow endpoint
- **THEN** the underlying request is aborted, no continuation fires afterward, and connection resources are released
