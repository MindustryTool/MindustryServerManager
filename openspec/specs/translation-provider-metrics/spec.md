# translation-provider-metrics Specification

## Purpose
Tracks rolling p95 latency and rolling/lifetime success rates for translation providers via `ProviderState`.

## Requirements

### Requirement: Rolling p95 latency tracking
`ProviderState` SHALL track the response latency in milliseconds for up to the last 100 successful requests using a fixed-size ring buffer, and provide a method to calculate the 95th percentile (p95) latency.

#### Scenario: No successful samples recorded
- **WHEN** no successful requests have been recorded for a provider
- **THEN** `getP95LatencyMillis()` returns `-1`

#### Scenario: Under 100 samples recorded
- **WHEN** between 1 and 99 successful requests have been recorded
- **THEN** `getP95LatencyMillis()` returns the 95th percentile value computed over all recorded samples

#### Scenario: Ring buffer wraparound
- **WHEN** more than 100 successful requests are recorded
- **THEN** older latency samples are overwritten so that `getP95LatencyMillis()` only reflects the most recent 100 successful requests

### Requirement: Rolling and lifetime success rate tracking
`ProviderState` SHALL track the outcome of translation requests to compute both a recent rolling success rate (last 100 attempts) and lifetime metrics (total requests, total successes, total failures).

#### Scenario: Cold start with no requests
- **WHEN** no translation attempts have been recorded
- **THEN** `getTotalRequests()` returns `0`
- **AND** `getRecentSuccessRate()` returns `100.0`
- **AND** `getLifetimeSuccessRate()` returns `100.0`

#### Scenario: Recording successful and failed requests
- **WHEN** translation attempts succeed or fail
- **THEN** lifetime counters `totalSuccesses` and `totalFailures` are incremented accordingly
- **AND** `getLifetimeSuccessRate()` returns `(totalSuccesses / totalRequests) * 100.0`
- **AND** `getRecentSuccessRate()` returns the percentage of successful attempts within the sliding window of the last 100 attempts

### Requirement: TranslationService execution measurement
`TranslationService` SHALL measure the execution time of each translation attempt and pass the duration to `ProviderState.recordSuccess` upon successful translation.

#### Scenario: Successful translation records duration
- **WHEN** a provider successfully translates text
- **THEN** `TranslationService` invokes `recordSuccess(providerName, durationMillis)` on the provider's `ProviderState`
- **AND** the provider's latency buffer and outcome buffer are updated
