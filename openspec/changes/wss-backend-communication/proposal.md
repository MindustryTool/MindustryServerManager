## Why

Currently, communication between the central Backend API (`api.mindustry-tool.com`) and the Server Manager daemon relies on two-way HTTPS:
1. The Server Manager calls outbound HTTPS endpoints on the Backend API using `accessToken`.
2. The Backend API makes direct inbound HTTPS calls and SSE subscriptions to the Server Manager on port 8088 using a shared `securityKey` to verify JWTs.

This architecture causes significant operational friction for hosting Server Managers:
- **Networking & NAT Traversal**: The manager host requires a public IP, domain name, or router port forwarding. It fails behind standard NAT/CGNAT or home firewalls.
- **SSL/TLS Complexity**: The manager must configure and renew SSL/TLS certificates or run an HTTPS reverse proxy (Nginx, Caddy, Cloudflare Tunnel).
- **Shared Secret Risk**: A symmetric `securityKey` must be securely shared between the backend and each manager instance.

Replacing inbound HTTPS with a single outbound WebSocket Secure (`wss://`) connection established by the Server Manager solves these issues: zero port forwarding, zero local SSL/TLS configuration, elimination of `securityKey`, and instant real-time duplex communication.

## What Changes

- **Outbound WSS Connection**: The Server Manager initiates and maintains a persistent outbound TLS WebSocket connection to the central Backend API (`wss://.../managers/gateway`).
- **Token Handshake Authentication**: Authentication occurs during the WSS upgrade handshake using the manager's persistent `accessToken`. **BREAKING**: Removes `securityKey` configuration (`SECURITY_KEY_V2`) and symmetric JWT validation from the manager.
- **Duplex RPC & Event Multiplexing**: RPC commands (hosting, pausing, executing commands, querying players) and outbound event streams (server events, usage metrics) multiplex over the WSS connection using the `WsMessage` envelope.
- **Binary Chunked File Streaming**: File uploads and downloads (maps, mods, save files) stream over the WebSocket using chunked binary frames and transfer sessions.
- **Javalin Server Surface Reduction**: **BREAKING**: All inbound public HTTP endpoints under `/api/v2/*` on Javalin port 8088 are removed. Javalin port 8088 is retained strictly for local Docker container plugins connecting to `/gateway`.
- **Connection Lifecycle & Reconnect**: Implements exponential backoff with jitter on disconnect, 20-second ping/pong heartbeats, and a `sync-state` inventory handshake upon connection.

## Capabilities

### New Capabilities
- `backend-wss-connection`: Outbound persistent WebSocket connection from Server Manager to central Backend API with Bearer token handshake, ping/pong heartbeats, reconnection backoff, and state synchronization.
- `backend-rpc-gateway`: Bidirectional RPC command dispatch and event streaming between Backend API and Server Manager using the `WsMessage` envelope.
- `chunked-file-streaming`: Binary frame chunked file transfer protocol over WebSocket for uploading and downloading maps, mods, and save backups.

### Modified Capabilities
<!-- No requirement changes to existing plugin capabilities -->

## Impact

- **Configuration (`EnvConfig`)**: Deprecates `SECURITY_KEY_V2` and `SERVER_URL`. Adds `BACKEND_WS_URL`. Retains `ACCESS_TOKEN_v2`.
- **Server Module (`server`)**:
  - `ServerMain.java`: Strips all `/api/v2/*` HTTP route registrations; retains only the `/gateway` WebSocket handler for local plugins; initializes the outbound WSS backend client.
  - New service `BackendWsClient`: Handles connection lifecycle, reconnect backoff, heartbeats, and frame dispatch.
  - New service/handler `BackendRpcHandler`: Routes incoming `WsMessage` commands to `ServerService` / `GatewayService` and forwards local events to Backend.
  - New service `BackendFileTransferService`: Manages file chunking, binary frame packing/unpacking, and transfer session tracking.
- **External Dependencies**: Utilizes Java 11+ standard `java.net.http.HttpClient` WebSocket client (`java.net.http.WebSocket`) already present in runtime without adding new heavy third-party dependencies.
