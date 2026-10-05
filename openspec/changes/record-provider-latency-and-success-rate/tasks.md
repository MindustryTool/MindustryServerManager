## 1. ProviderState Metrics Implementation

- [ ] 1.1 Add rolling latency ring buffer (size 100) and `getP95LatencyMillis()` to `ProviderState`
- [ ] 1.2 Add rolling outcomes buffer (size 100) and lifetime counters (`totalSuccesses`, `totalFailures`) to `ProviderState`
- [ ] 1.3 Add query methods for success rates (`getRecentSuccessRate()`, `getLifetimeSuccessRate()`, `getTotalRequests()`) to `ProviderState`
- [ ] 1.4 Update `recordSuccess(String providerName, long durationMillis)` overload and update `recordFailure` to track outcomes

## 2. TranslationService Timing Integration

- [ ] 2.1 Measure execution duration around `provider.translate(...)` in `TranslationService.translate(...)`
- [ ] 2.2 Forward measured latency to `candidate.state().recordSuccess(provider.name(), durationMillis)`

## 3. Verification & Testing

- [ ] 3.1 Write unit tests for `ProviderState` metrics (p95 latency calculation with empty/partial/overflow buffers)
- [ ] 3.2 Write unit tests for `ProviderState` success rate calculations (recent window vs. lifetime)
- [ ] 3.3 Verify existing translation tests pass and verify end-to-end latency recording in `TranslationServiceTest`
