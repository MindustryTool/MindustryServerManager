# kick-ip-management

## Purpose
Manage kicked IPs across nodes with gateway handlers and Server Manager REST endpoints for querying and unbanning/removing kicked IPs.

## Requirements

### Requirement: Delete Kicked IP Gateway Handler
`ApiGateway` SHALL register a message handler `delete-kicked-ip` (or `remove-kicked-ip`) that removes a specified IP address from `Vars.netServer.admins.kickedIPs`.

#### Scenario: Successfully remove kicked IP via gateway
- **WHEN** `ApiGateway` receives a `delete-kicked-ip` message with an IP string
- **THEN** the IP is removed from `Vars.netServer.admins.kickedIPs` on the main thread
- **AND** a success result is returned

### Requirement: Server Manager Fetch Recent Players Endpoint
The Server Manager HTTP API SHALL expose `GET /api/v2/servers/{id}/recent-players` which requests `get-recent-players` via `GatewayService` and returns a JSON array of `RecentPlayerDto`.

#### Scenario: Client requests recent players for server
- **WHEN** an authenticated client sends `GET /api/v2/servers/{id}/recent-players`
- **THEN** the Server Manager proxies the request to the server's gateway and returns the list of `RecentPlayerDto` with status 200

### Requirement: Server Manager Kicked IP Endpoints
The Server Manager HTTP API SHALL expose `GET /api/v2/servers/{id}/kicks` to fetch current kicked IPs and `DELETE /api/v2/servers/{id}/kicks/{ip}` to remove a kicked IP.

#### Scenario: Client deletes kicked IP for server
- **WHEN** an authenticated client sends `DELETE /api/v2/servers/{id}/kicks/{ip}`
- **THEN** the Server Manager calls `GatewayService` to remove the kicked IP from the target server node
- **AND** returns status 200 or 204
