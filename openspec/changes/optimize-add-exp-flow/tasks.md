## 1. Core Lock-Free Exp Updates in SessionData

- [ ] 1.1 Implement thread-safe, non-blocking atomic exp addition in `SessionData` (using CAS loop on raw int bits or atomic updater)
- [ ] 1.2 Add unit tests validating concurrent exp increments and fractional precision preservation in `SessionData`

## 2. Optimize SessionService Exp Processing and Level Updates

- [ ] 2.1 Remove `synchronized (data)` monitor lock from `SessionService.onExpGain` and utilize non-blocking atomic exp addition
- [ ] 2.2 Guard `session.player.name(...)` in `SessionService.updateLevel` so that name formatting and network sync occur only when `level != session.currentLevel`
- [ ] 2.3 Deduplicate redundant `sessionRepository.markDirty(session)` invocations in the exp gain processing flow

## 3. Decouple JSON Serialization from SessionRepository Mutex

- [ ] 3.1 Refactor `SessionRepository.write()` to extract a lightweight snapshot of `SessionData` and perform `JsonUtils.toJsonString` outside the critical section
- [ ] 3.2 Ensure background `flushBatch` persistence cannot block game thread or concurrent exp increments

## 4. Verification and Regression Testing

- [ ] 4.1 Run full suite of session, exp, and command tests (`ExpUtilsTest`, `SessionServiceTest`, `ServerExpCommandTest`, `SessionRepositoryTest`)
- [ ] 4.2 Add high-volume concurrent exp gain test to verify correctness, thread safety, and suppressed name sync packets
