# Mindustry Server Manager - Server Project Documentation

This document provides a comprehensive overview of the Mindustry Server Manager (Server component) architecture, components, and data flows, prepared for a full project rewrite.

## 1. Project Overview

- **Tech Stack**: Java 17, Spring Boot 3.3.4 (WebFlux), Gradle.
- **Key Dependencies**:
    - `spring-boot-starter-webflux`: Reactive web framework.
    - `spring-boot-starter-security`: Reactive security.
    - `docker-java`: Docker API interaction.
    - `arc-core` & `mindustry-core`: Mindustry game logic and file handling.
    - `java-jwt`: JWT authentication.
    - `caffeine`: Local caching.
    - `lombok`: Boilerplate reduction.
- **Environment**: Supports `DEV` and `PROD` modes via `ENV` environment variable.

---

## 2. Core Architecture

The server acts as an orchestrator for multiple Mindustry server instances running in Docker containers.

### 2.1 Gateway System
The `GatewayService` is the heart of communication between the Manager and individual Mindustry servers.
- **GatewayClient**: Represents a connection to a specific Mindustry server's internal API (default port 9999).
- **Event Streaming**: Each client subscribes to an SSE (Server-Sent Events) stream from the Mindustry server, emitting events (logs, player joins, state changes) to a global `EventBus`.
- **API Wrapper**: Provides a reactive interface for sending commands, hosting maps, and managing players on specific instances.

### 2.2 Node Management
Managed by `NodeManager` (implemented by `DockerNodeManager`).
- **Docker Integration**: Uses Docker labels (`com.mindustry-tool.server.v2`) to track metadata and identify managed containers.
- **Lifecycle**: Handles container creation (with specific CPU/RAM limits), removal, and image pulling.
- **Statistics**: Streams real-time container CPU and Memory usage via `statsCmd`.
- **Filesystem**: Maps host directories (`./data/servers/{id}/config`) to container `/config` for persistence.

### 2.3 Orchestration & Crons
`ServerService` coordinates high-level operations.
- **Auto-Hosting**: A cron job that ensures servers marked for auto-start are running.
- **Auto-Shutdown**: A cron job that stops servers with no players after a certain period to save resources.
- **Health Monitoring**: Detects non-responsive servers and flags them for restart/kill.

---

## 3. Component Breakdown

### 3.1 Controllers (`server.controller`)
- **`ServerController`**: Main API for frontend interaction. Handles file management, server controls (host/pause/remove), event streams, and player/mod/map listings.
- **`GatewayController`**: Specialized endpoints for gateway operations like login and connection requests.
- **`ManagerController`**: Health check ("pong") endpoint.

### 3.2 Services (`server.service`)
- **`ServerService`**: Business logic for server lifecycle, hosting flows, and file I/O coordination.
- **`GatewayService`**: Manages the pool of `GatewayClient` connections and heartbeats.
- **`EventBus`**: In-memory event dispatcher for decoupled component communication.
- **`ApiService`**: External integration with `mindustry-tool.com` (e.g., for map preview generation).
- **`DockerService`**: Utilities for inspecting the manager's own Docker environment.

### 3.3 Managers (`server.manager`)
- **`DockerNodeManager`**: Concrete implementation of server container management using Docker.
- **`LocalNodeManager`**: Placeholder for future non-Docker deployments.

### 3.4 Security & Filters (`server.filter`)
- **`SecurityConfig`**: Reactive security setup (CORS, CSRF disable).
- **`SecurityFilter`**: JWT-based authentication. Validates `Bearer` tokens against a `securityKey`.
- **`RequestFilter`**: Logging filter that tracks request duration, status, and URL.
- **`GlobalErrorWebExceptionFilter`**: Global reactive error handler that returns `ApiError` as JSON.

### 3.5 Utilities (`server.utils`)
- **`Utils`**: Jackson ObjectMapper configuration, image processing, map/mod metadata loading, and WebClient error wrapping.
- **`FileUtils`**: Secure file operations (path traversal protection), recursive deletion, and directory listing.
- **`ApiError`**: Custom exception type for structured API errors with HTTP status codes.

---

## 4. Key Data Flows

### 4.1 Server Hosting Flow
1. **Request**: Frontend calls `POST /api/v2/servers/{id}/host`.
2. **Container Setup**: `ServerService` calls `NodeManager.create()`.
3. **Gateway Connection**: `ServerService` waits for the container to start and `GatewayService` to establish a client connection.
4. **Command Sequence**: `ServerService` sends a sequence of Mindustry commands (name, desc, port, gamemode) via the gateway.
5. **Final Host**: `ServerService` triggers the `host` command on the server.

### 4.2 Event Stream Flow
1. **Source**: Mindustry server generates an event (e.g., `PlayerJoin`).
2. **Gateway**: `GatewayClient` receives the event via SSE.
3. **Bus**: `GatewayClient` emits the event to `EventBus`.
4. **Broadcast**: `ServerService` (via its registered handler) sends the event to all active SSE sinks connected to `GET /api/v2/events`.

---

## 5. Configuration (`server.EnvConfig`)

Properties prefixed with `app`:
- **Docker**:
    - `mindustryServerImage`: Default Docker image for servers.
    - `serverDataFolder`: Root path for server data.
- **Server**:
    - `accessToken`: Token for manager-to-central-API auth.
    - `securityKey`: Secret for JWT validation.
    - `dataFolder`: Local data folder.
    - `serverUrl`: Base URL for the manager.

---

## 6. Important Constants (`server.config.Const`)
- **Ports**: Default 6567, Max 20000.
- **Labels**: `com.mindustry-tool.server.v2` (Metadata), `com.mindustry-tool.server.id.v2` (ID).
- **Paths**: Defaults to `./data` in DEV mode.
- **Limits**: Max file upload size 5MB.
