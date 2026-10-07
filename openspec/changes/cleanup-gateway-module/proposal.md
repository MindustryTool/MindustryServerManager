## Why

The `:gateway` module has grown to ~3,400 lines of production code. Repetition and dead code now hide the real behavior: the same pending-request cleanup appears five times, the same stream-settlement branch four times, and several methods/fields are never referenced. This noise raises the cost of every future change and makes review harder. Cleanup now is low-risk because the module has a dense test suite pinning its behavior.

## What Changes

Pure refactor. No behavior change, no wire-protocol change, no change to public API shape or threading/session semantics.

- Remove unused internal code (methods, fields, an unused constructor).
- Extract repeated inline logic into named helpers:
  - pending-request take/cleanup (`failPending` / `failReplyRequest` / timeout lambdas / response handling)
  - stream failure settlement (`if (isReply) failReplyRequest else sendStreamError`)
  - client/server subscription teardown (cancel timeout, mark closed, complete ack, run callbacks)
  - `JsonNode` → typed conversion (`convertParam` / `convertStreamMeta`)
  - close-cause unwrapping (`rootCause` in `JdkWsClient` and `Transport`)
- Deduplicate message builders (`errorFor` / `ackFor`, stream start/done construction in `sendStreamBytes` vs `emitReplyStream`).
- Reuse a single SHA-256 helper instead of two copies.
- Small consistency fixes (Lombok vs manual constructors, repeated terminated-guard in `Transport.InnerListener`).
- Optional (separate, only if approved): split `WsRpcChannel`'s stream and subscription state into collaborators.

Explicitly out of scope: removing public `JdkWsClient.getUri()` (used outside this repo potentially), any change to `registerStreamHandler`'s public `responseType` parameter, and any behavior-affecting refactor.

## Capabilities

### New Capabilities

- `gateway-code-health`: internal structural requirements for the `:gateway` module (single source of truth for pending-request cleanup, shared close-cause unwrapping, no unreferenced internal members, and reused message/hash builders). This captures the refactor's verifiable outcomes without restating user-visible behavior.

### Modified Capabilities

None. No spec-level requirement changes; all existing gateway/transport/stream/subscription behavior requirements must continue to hold unchanged.

## Impact

- Affected code: `gateway/` module only (`WsMessage`, `client/*`, `rpc/WsRpcChannel`, `stream/*`).
- Affected behavior: none intended. Existing tests in `gateway/src/test` must pass unchanged.
- Public API: unchanged. Only internally-scoped (`package-private`/`private`) members are removed or merged.
- Dependencies/systems: none. No build or dependency changes.
- Risk: behavior regression in concurrency-sensitive paths if a helper is mis-extracted; mitigated by the existing test suite and by keeping each extraction mechanical.
