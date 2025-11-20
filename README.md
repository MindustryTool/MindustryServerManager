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
-  Go to mindustry-tool.com, create a new server manager, get SECURITY_KEY, ACCESS_TOKEN
-  Update docker-compose.yml with SECURITY_KEY, ACCESS_TOKEN (you should keep it secret, you can use .env or edit vps env)
-  Rerun server manager: ` docker compose down` `docker compose up`
