# MindustryServerManager

## Features

-  Host Mindustry servers with configurable `port`, `mode`, `image`, `env`
-  Start/stop lifecycle with real-time SSE events
-  Live server state: players, map, mods, status, chat, logs
-  Resource usage metrics: CPU and RAM
-  Console commands: list available and execute commands
-  Player management: list players and update admin/login info
-  File management: upload, list, delete files; create folders
-  Maps inventory: list installed maps
-  Mods inventory: list installed mods
-  Config diff: detect mismatches between desired and running config

## Requirements

-  Docker latest version
-  SSL and HTTPS is required for security
-  At least 4GB of ram

## Setup server

-  Run setup.sh: `./setup.sh`
-  Go to mindustry-tool.com, create a new server manager, get ACCESS_TOKEN
-  Update ./config/.env with ACCESS_TOKEN and BACKEND_WS_URL (you should keep the token secret, you can use .env or edit vps env). No SECURITY_KEY, port forwarding, or TLS setup is needed: the manager dials the Backend API over outbound WSS
-  Generate the required gateway signing key and add it to ./config/.env:

   ```sh
   echo "GATEWAY_SIGNING_KEY=$(openssl rand -base64 32)" >> ./config/.env
   ```

   Boot fails fast when `GATEWAY_SIGNING_KEY` is missing or blank. The key signs the gateway JWTs stored in each server's `server.json`, so tokens stay valid across restarts.
-  Rerun server manager: ` docker compose down` `docker compose up`

## Rotating the gateway signing key

The key is a hard cut: change `GATEWAY_SIGNING_KEY`, then restart the manager. Each server fails one dial, its `server.json` JWT is rewritten with the new key, and it reconnects on its own. Old and new keys are never accepted at the same time.
