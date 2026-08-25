## ADDED Requirements

### Requirement: Schedule node semantics
The Schedule node SHALL support one-shot (`after delay/ticks`), repeating (`every interval`), next-tick, and condition-gated scheduling, emitting a cancellable ScheduleHandle output; scheduled actions SHALL fire on the Mindustry main thread via the existing tick scheduler without creating worker threads.

#### Scenario: One-shot schedule fires late
- **WHEN** a graph schedules "send message" 5 seconds after PlayerJoin
- **THEN** the message sends exactly once on the main thread approximately 5 seconds later

#### Scenario: Repeating schedule with handle
- **WHEN** a repeating every-10s schedule's handle feeds a Cancel node which executes
- **THEN** no further fires occur after cancellation

### Requirement: Non-blocking Delay node
The Delay node SHALL suspend the current execution (RUNNING → SUSPENDED → SCHEDULED) and resume it on the main thread after the configured duration using the tick scheduler/event loop; it MUST NOT block any thread or use `Thread.sleep`.

#### Scenario: Join-delay-welcome flow
- **WHEN** PlayerJoin → Delay 5s → Send Message runs for three players joining together
- **THEN** each player receives their own message ~5s after their join, interleaved safely with other executions

### Requirement: Await node semantics
Await SHALL suspend until a Future-typed input completes, then resume the continuation on the main thread with the result or typed failure; timeouts MAY wrap the await.

#### Scenario: Await network result on main thread
- **WHEN** Network GET completes on an IO thread and flows into Await
- **THEN** downstream nodes execute on the main thread receiving the HttpResponse value

### Requirement: Cancellation and shutdown integration
Pending schedules and suspended delays/awaits SHALL be cancelled when their execution is cancelled, their graph disabled, or the server shuts down; shutdown MUST complete without leaking timers or resuming dead contexts.

#### Scenario: Clean shutdown with pending delays
- **WHEN** the server stops while executions await delays
- **THEN** shutdown proceeds promptly, marks those executions CANCELLED, and no resume fires afterward
