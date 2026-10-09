## 1. ProviderState Higher-Order Function

- [x] 1.1 Add `ProviderAction` functional interface and `execute(String providerName, ProviderAction<TranslationResponse> action)` to `ProviderState`
- [x] 1.2 Encapsulate duration timing, success recording, null/blank detection, and failure recording inside `execute`

## 2. TranslationService Refactoring

- [x] 2.1 Remove `order` from `RegisteredProvider` record and remove 3-arg `registerProvider` overload
- [x] 2.2 Refactor round-robin scheduler to use atomic counters per tier without incrementing on retries
- [x] 2.3 Refactor execution block in `TranslationService.translate` to use `candidate.state().execute(...)`
- [x] 2.4 Update tests in `TranslationServiceTest` and verify fair rotation under retries
- [x] 2.5 Run full test suite to ensure clean build
