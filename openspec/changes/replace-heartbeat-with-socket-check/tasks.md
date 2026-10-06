## 1. Liveness state

- [x] 1.1 Remove `HEARTBEAT_TIMEOUT_DURATION`, `lastHeartBeatAt`, and its updates in `onMessage`, `onBinary`, `onOpen`
- [x] 1.2 Add `lastDisconnectAt` plus `DISCONNECT_WARN_AFTER` of 60s, keep `TERMINATE_CONNECTION_AFTER` of 3min, add `isSocketClosed()` helper on `session == null || !isOpen()`
- [x] 1.3 Update `onOpen` to clear clock to `null` and `onClose` to set clock to `now` with volatile visibility

## 2. Warn and kill paths

- [x] 2.1 Rewrite `shouldTerminate()` to require `lastDisconnectAt != null` plus past 3min plus socket closed, with no `isRunning` check
- [x] 2.2 Rewrite disconnect warn to require `lastDisconnectAt != null` plus past 60s plus socket closed, with socket-based log text and no `isRunning` check
- [x] 2.3 Keep scheduler kill-first then warn order and confirm removed clients skip warn in same tick

## 3. Tests

- [x] 3.1 Add clock lifecycle tests: init equals create time, open clears, close sets, reconnect resets
- [x] 3.2 Add warn and kill matrix tests: open-idle quiet, closed warns at 60s and kills at 3min, never-linked dies at 3min, flap gets fresh grace
- [x] 3.3 Run existing gateway and server tests and confirm no heartbeat refs or false kills remain
