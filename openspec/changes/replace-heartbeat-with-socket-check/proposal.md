## Why

App-level heartbeat in `GatewayClient` false-fires for idle but live nodes. Plugin sends only on events and relies on `JdkWsClient` ping/pong as keepalive. Time since last message is not proof of dead link. Socket state plus disconnect age is true.

## What Changes

- Remove `HEARTBEAT_TIMEOUT_DURATION`, `lastHeartBeatAt`, and message-time updates in `onMessage`, `onBinary`, `onOpen`.
- Add `lastDisconnectAt`: init to create time, clear to `null` on `onOpen`, set to `now` on `onClose`.
- Define not-connected as `rpcChannel.getSession() == null || !session.isOpen()`.
- Warn when `lastDisconnectAt != null` and `now > lastDisconnectAt + 60s` and socket still closed. Log only. No `isRunning` check.
- Terminate with `NOT_CONNECTED` when `lastDisconnectAt != null` and `now > lastDisconnectAt + 3min` and socket still closed. No `isRunning` check.
- `CONNECTING` with no socket warns at 60s and dies at 3min from create time.
- `CONNECTED` with open socket never warns or kills, even if idle.
- Reconnect clears clock and grants fresh grace.
- Half-open socket (open but no app traffic) stays quiet. Client ping/pong owns that case.

## Capabilities

### New Capabilities

- `gateway-client-liveness`: Covers server-side gateway client warn and terminate based on socket state plus disconnect age.

### Modified Capabilities

<!-- None -->

## Impact

- `server/src/main/java/server/service/GatewayService.java`: `GatewayClient` liveness fields, `onOpen`, `onClose`, `onMessage`, `onBinary`, `shouldTerminate`, `checkHeartbeat`, scheduler loop.
- No API change. No DB change. No new deps.
- Behavior change: idle live nodes no longer warn or die. Dead sockets warn at 60s and die at 3min.
