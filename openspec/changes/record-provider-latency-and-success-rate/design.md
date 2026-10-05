## Context

`TranslationService` routes translation requests to various providers (e.g. Google Web, Lingva, DeepL, etc.) categorized into priority tiers. Each provider instance has an associated `ProviderState` that tracks consecutive failures and calculates backoff cooldown durations.

Currently, `ProviderState` does not capture latency or success rate metrics. When evaluating provider health or triaging slow translation times, operators have no in-memory or programmatic view into whether a provider is responding quickly or consistently failing.

## Goals / Non-Goals

**Goals:**
- Maintain a rolling p95 latency for each translation provider based on the last 100 successful requests.
- Track provider success rates both as a rolling metric (outcomes across the last 100 attempts) and as lifetime counters (total requests, total successes, total failures).
- Measure translation duration in `TranslationService` and record duration on success.
- Ensure thread safety with negligible overhead and bounded memory ($O(1)$ allocations during steady-state).
- Expose clear query methods on `ProviderState` for observability and tests.

**Non-Goals:**
- Altering provider routing or tripping cooldowns based on high latency (this is strictly observability-focused).
- Recording latency for failed or timed-out requests (cooldown backoff already manages failures).
- Introducing external metric dependencies (such as Micrometer, Dropwizard, or HdrHistogram).

## Decisions

### 1. Rolling Ring Buffer with Fixed Window Size (N = 100)
- **Rationale**: An array of 100 `long` primitives for latency and 100 `boolean` primitives (or flags) for outcomes has an infinitesimal memory footprint (~few hundred bytes per provider) and zero garbage collection pressure once allocated.
- **Percentile Calculation**: When `getP95LatencyMillis()` is requested, copy the valid entries into a temporary array and sort them. The p95 value is selected at index $\lceil 0.95 \times (k - 1) \rceil$. Since $k \le 100$, sorting takes under a microsecond and occurs only upon query.
- **Alternatives considered**:
  - *Full external metrics library (e.g., Micrometer)*: Overkill and introduces unnecessary external dependencies.
  - *Dynamic list with time-decay*: Incurs frequent object allocations and requires background pruning threads.

### 2. Track Both Recent Window and Lifetime Totals
- **Rationale**: Lifetime totals provide broad historical context since startup (`totalSuccesses`, `totalFailures`), while the rolling 100-attempt window allows fast recovery and reflects recent provider health.
- **Alternatives considered**:
  - *Lifetime only*: Sluggish to recover after a transient outage.
  - *Sliding window only*: Loses overall perspective if traffic is sparse.

### 3. Record Latency Strictly on Success
- **Rationale**: Failures are already punished via exponential backoff cooldowns. Measuring partial failure durations or timeout lengths could artificially inflate latency percentiles.

## Risks / Trade-offs

- [Concurrent synchronization contention] → `ProviderState` already synchronizes on state updates (`recordSuccess`, `recordFailure`). Using the same lock ensures thread safety without noticeable contention given typical translation request frequencies.
- [Cold start / insufficient samples] → When fewer samples exist, p95 is calculated over available samples, or returns `-1` if no successful samples have been recorded yet. Success rate returns `100.0` or `-1` if no requests have occurred yet.
