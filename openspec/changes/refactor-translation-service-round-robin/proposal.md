## Why

`TranslationService` currently has unused `order` metadata in `RegisteredProvider`, and round-robin counter increments on retries disturb the even distribution of requests across providers in a tier. Furthermore, callers manually orchestrate timing, error handling, null-checking, and state transitions across `ProviderState.recordSuccess` and `ProviderState.recordFailure`.

Encapsulating execution within a higher-order function in `ProviderState` and using an atomic request-level round-robin scheduler reduces boilerplate, prevents retry ripples, and ensures even distribution among providers to minimize HTTP 429 status codes.

## What Changes

- Remove unused `order` attribute from `RegisteredProvider` and simplify `registerProvider(int tier, TranslationProvider provider)`.
- Introduce higher-order function `ProviderState.execute(String providerName, Callable<TranslationResponse> action)` to handle duration timing, null/blank detection, and automatic success/failure recording.
- Update round-robin rotation in `TranslationService` to increment tier counters per initial request attempt only, avoiding counter skew and unfair load distribution during internal retry attempts.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `chat-translation`: Update provider registration contract (removing intra-tier order) and specify retry-isolated round-robin rotation.

## Impact

- `server.service.translation.ProviderState`
- `server.service.translation.TranslationService`
- `server.service.translation.provider.*` (tests and registrations)
- Unit tests updating `registerProvider` signatures and verifying fair distribution.
