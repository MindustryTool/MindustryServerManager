# Tasks: Cleanup Gateway Module

## 1. Baseline and dead code

- [ ] 1.1 Run `./gradlew :gateway:test` and record the green baseline
- [ ] 1.2 Remove `JdkWsClient.backoffDelayMillis(int)` (unused)
- [ ] 1.3 Remove `Transport.isTerminated()` and `Transport.isSenderAlive()` (unused)
- [ ] 1.4 Remove the unused 5-arg `Transport` constructor; keep `DEFAULT_SEND_TIMEOUT`
- [ ] 1.5 Remove `FileChunkReceiver.chunkCount(UUID)` (unused; keep `hasTransfer`)
- [ ] 1.6 Remove the never-read `StreamHandlerEntry.responseType` field (keep the public parameter)
- [ ] 1.7 Build and run `./gradlew :gateway:test`

## 2. Duplication helpers

- [ ] 2.1 Add a pending-request take-and-settle helper (remove future + timeout, complete exceptionally)
- [ ] 2.2 Route the two timeout lambdas through the helper
- [ ] 2.3 Replace `failReplyRequest` with a call to the shared pending helper
- [ ] 2.4 Route the inbound-response path (`handleTextMessage`) through the helper
- [ ] 2.5 Add `settleStreamFailure(slot, detail)` and replace the four `handleStreamDone` branches
- [ ] 2.6 Use `settleStreamFailure` for the ingest-time failure sites
- [ ] 2.7 Extract one `rootCause` helper and use it from `JdkWsClient` and `Transport`
- [ ] 2.8 Add `closeClientSubscription(slot, err)` and reuse in `handleSubscriptionError`, `unsubscribe`, and `failAll`
- [ ] 2.9 Add `closeServerSubscription(slot)` and reuse in `handleUnsubscribe` and `failAll`
- [ ] 2.10 Build and run `./gradlew :gateway:test`

## 3. Builder and helper reuse

- [ ] 3.1 Delete `FileChunkReceiver.sha256Hex`; call `FileChunkStreamer.sha256Hex`
- [ ] 3.2 Add one `deserialize(JsonNode, Class, String)` and back `convertParam`/`convertStreamMeta`
- [ ] 3.3 Add one reply/error frame builder and back `errorFor`/`ackFor`
- [ ] 3.4 Make `FileTransferHeader.encodeFrame` reuse `encode()` for the header prefix
- [ ] 3.5 Collapse `WsMessage.withPayload`/`setPayload` to one and share a private response builder for `response`/`error`
- [ ] 3.6 Reuse the stream start/done builders between `sendStreamBytes` and `emitReplyStream`
- [ ] 3.7 Build and run `./gradlew :gateway:test`

## 4. Structural tidy-ups

- [ ] 4.1 Extract a `tryRetire(transport)` guard shared by `JdkWsClient.onDrop` and `onKick`
- [ ] 4.2 Replace repeated terminated-guard checks in `Transport.InnerListener` with one helper
- [ ] 4.3 Normalize `HandlerEntry`/`StreamHandlerEntry` construction style
- [ ] 4.4 Delete now-obsolete private methods, then build and run `./gradlew :gateway:test`

## 5. Verification

- [ ] 5.1 Run `./gradlew :gateway:test` with no test modifications; confirm all pass
- [ ] 5.2 Repo-wide reference search to confirm no removed public signature was used by `server`/`plugin`
- [ ] 5.3 Run `openspec validate cleanup-gateway-module --strict` (or the project's validation command)
- [ ] 5.4 Review the final diff for any accidental behavioral change
