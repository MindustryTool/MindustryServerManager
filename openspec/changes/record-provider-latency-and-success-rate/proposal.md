## Why

`TranslationService` selects and manages translation providers across tiers, maintaining a `ProviderState` per provider to track consecutive failures and cooldown backoffs. However, operators and monitoring systems lack visibility into provider performance and health: there is no way to inspect p95 latency or success rates. Adding in-memory latency and success tracking to `ProviderState` allows assessing provider quality and debugging degraded translation performance without introducing external monitoring dependencies.

## What Changes

- Add rolling p95 latency tracking (last 100 successful requests) to `ProviderState`.
- Add success rate tracking to `ProviderState`, supporting both a rolling window (last 100 requests) and lifetime counts (total requests, successes, failures).
- Update `TranslationService.translate(...)` to record the execution duration of successful provider translations.
- Expose observability getters on `ProviderState` (e.g. `getP95LatencyMillis()`, `getRecentSuccessRate()`, `getLifetimeSuccessRate()`, `getTotalRequests()`, `getTotalSuccesses()`, `getTotalFailures()`).

## Capabilities

### New Capabilities
- `translation-provider-metrics`: Tracks rolling p95 latency and rolling/lifetime success rates for translation providers via `ProviderState`.

### Modified Capabilities
<!-- No requirement changes to existing capabilities -->

## Impact

- `server/src/main/java/server/service/translation/ProviderState.java`: Internal state structures, ring buffers, and new query methods.
- `server/src/main/java/server/service/translation/TranslationService.java`: Timing provider execution and passing elapsed millis to `recordSuccess`.
- Unit tests in `server/src/test/java/server/service/TranslationServiceTest.java` (and new `ProviderStateTest`).
- No breaking API changes; routing decisions and cooldown behaviors remain unaffected.
