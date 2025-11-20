# MindustryServerManager

Server manager:

-  Auto translate chat
-  Server cluster:

   -  Server cluster hub
   -  Admin managment, ban user, ip ban, user info
   -  File managment
   -  Custom command, command auto complete

-  Server auto backup (file)
-  Anti grief, nsfw
-  Transfer server

## Setup server

-  Run setup.sh: `./setup.sh`
-  Go to mindustry-tool.com, create a new server manager, get SECURITY_KEY, ACCESS_TOKEN
-  Update docker-compose.yml with SECURITY_KEY, ACCESS_TOKEN (you should keep it secret, you can use .env or edit vps env)
-  Rerun server manager: ` docker compose down` `docker compose up`
