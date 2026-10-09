## Context

In `TranslationService`, multiple providers (Bing, Lingva, Google Direct) exist in Tier 1. Currently:
1. `RegisteredProvider` keeps an `order` field that has no practical effect because round-robin selection dynamically overrides it.
2. `tierRoundRobinIndices` increments on every retry within a single request, which perturbs round-robin fairness for subsequent requests.
3. Callers manually call `System.currentTimeMillis()`, catch exceptions, check null/blank results, and invoke `recordSuccess` or `recordFailure`.

## Goals / Non-Goals

**Goals:**
- Provide a clean higher-order `execute` method on `ProviderState` that encapsulates latency timing, null/blank result checking, and state updates.
- Remove `order` parameter from `RegisteredProvider` and `registerProvider`.
- Maintain a clean per-tier `AtomicInteger` that increments once per request entry into a tier, using relative offsets for retries.

**Non-Goals:**
- Altering the multi-tier escalation hierarchy (Tier 0 proxied vs Tier 1 direct).
- Changing external API or wire protocol contracts.

## Decisions

### 1. Higher-Order Execution in `ProviderState`
- **Decision**: Define `execute(String providerName, ProviderAction<TranslationResponse> action)` where `ProviderAction` can throw `Exception`.
- **Behavior**:
  - Measures elapsed time.
  - If action returns valid non-blank `TranslationResponse`, invokes `recordSuccess` and returns the response.
  - If action returns `null` or blank text, invokes `recordFailure(providerName, null)` and returns `null`.
  - If action throws an `Exception`, invokes `recordFailure(providerName, e)` and rethrows the exception (so callers can log the exact failure message if needed).

### 2. Fair Tier Round-Robin without Retry Ripple
- **Decision**: Maintain a `ConcurrentHashMap<Integer, AtomicInteger> tierIndices`.
- **Behavior**:
  - When a request attempts a tier for the first time, compute `baseIndex = tierIndices.get(tier).getAndIncrement()`.
  - For retries within the same tier for that request, select `eligible.get((baseIndex + retryOffset) % eligible.size())`.
  - This ensures sequential requests advance through providers in order without skipping when a retry occurs.

### 3. Streamlined `RegisteredProvider`
- **Decision**: Redefine `public record RegisteredProvider(int tier, TranslationProvider provider, ProviderState state)`.
- Deprecate/remove the 3-argument `registerProvider(int tier, int order, TranslationProvider provider)` in favor of `registerProvider(int tier, TranslationProvider provider)`.

## Risks / Trade-offs

- **[Risk] Test signature breakage**: Tests calling `registerProvider(tier, order, provider)` directly will need signature updates.
  - Mitigation: Overload or update test fixtures in `TranslationServiceTest`.
