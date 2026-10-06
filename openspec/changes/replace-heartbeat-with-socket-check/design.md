## Context

`GatewayService.GatewayClient` tracks one entry per `serverId`. Today it uses app message age (`lastHeartBeatAt` set in `onMessage`, `onBinary`, `onOpen`) for two jobs: 60s log warn gated on `nodeManager.isRunning`, and 3min kill gated on time only. Socket state is ignored for kill.

Plugin sends only on events and relies on `JdkWsClient` ping (20s) plus pong deadline (45s) as keepalive. Idle live nodes thus look dead to the app clock. `WsRpcChannel.onClose` resets `ready` to empty, so `getSession()` returns `null` right after close. This makes live socket check reliable.

Stakeholder: server manager node lifecycle. Constraint: keep 60s and 3min values, change base only.

## Goals / Non-Goals

**Goals:**

- Kill and warn from socket state plus disconnect age, not message age.
- Stop false warn and kill for idle live nodes.
- Single clock for warn and kill.
- Keep reconnect grace and `CONNECTING` cleanup.

**Non-Goals:**

- No change to `JdkWsClient` ping, pong, or reconnect.
- No change to Docker remove or event flow beyond trigger condition.
- No backstop kill for half-open sockets with open transport.
- No new API, DB, or config.

## Decisions

**D1: Not-connected means live session check.**
Use `rpcChannel.getSession() == null || !session.isOpen()`.
Rationale: true transport state. `onClose` clears session to `null`, so clean close is caught at once. State flag alone can go stale if close is missed.
Alternative: flag only (cheap but stale), both flag and transport (safest, one more check). Chose transport per user pick.

**D2: One disconnect-age clock.**
Add `lastDisconnectAt`: init to create time, clear to `null` on `onOpen`, set to `now` on `onClose`.
Rationale: disconnect age matches "not connected for X" intent. Heartbeat age mixes idle with dead.
Alternative: keep heartbeat clock (false fires), dual clocks (harder to hold). Chose single clock.

**D3: Warn at 60s and kill at 3min on same clock, no `isRunning`.**
Warn: `lastDisconnectAt != null` and past 60s and socket closed. Kill: same past 3min and socket closed.
Rationale: same truth for both rungs. Drops per-tick Docker list cost. Values stay familiar.
Alternative: keep `isRunning` in warn (less noise when container gone, more cost). Dropped per user pick.

**D4: Delete heartbeat fully.**
Remove `HEARTBEAT_TIMEOUT_DURATION`, `lastHeartBeatAt`, and its updates. `onMessage` and `onBinary` only forward to channel.
Rationale: single source of truth. Dead field invites reuse bugs.
Alternative: keep unused field (confusing). Rejected.

**D5: Accept half-open gap.**
Open socket with no app traffic stays quiet. No kill.
Rationale: client ping/pong will drop dead transport, which then drives `onClose` and starts the clock.
Alternative: time backstop kill (brings back false kills). Rejected per user pick.

## Risks / Trade-offs

- [Half-open stays quiet] → Mitigation: client 20s ping and 45s pong deadline aborts dead link, server sees close and clock starts.
- [Late close after reconnect flips state] → Mitigation: kill reads live session at check time, and `onOpen` clears clock for fresh grace.
- [Slow starter killed at 3min] → Mitigation: same as old behavior. Startup should open fast. Tune later if needed.
- [Warn without `isRunning` fires when container gone] → Mitigation: kill at 3min cleans entry. Accepted noise window.
- [Warn text change] → Mitigation: rename from heartbeat timeout to socket disconnected so logs read true.
