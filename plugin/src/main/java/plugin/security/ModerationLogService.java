package plugin.security;

import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.EventType.PlayerBanEvent;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.database.Database;
import plugin.database.schema.BanLogs;
import plugin.database.schema.KickLogs;
import plugin.event.KickEvent;
import plugin.event.PlayerKickEvent;

@Component
public class ModerationLogService {
    private final Database database;

    public ModerationLogService(Database database) {
        this.database = database;
    }

    @Init
    public void init() {
        createTablesIfNotExists();
    }

    public void createTablesIfNotExists() {
        try {
            database.db().createTableIfNotExists(BanLogs.TABLE);
            database.db().createTableIfNotExists(KickLogs.TABLE);
        } catch (Exception e) {
            Log.err("Failed to create moderation log tables", e);
        }
    }

    public void logBan(String action, String userId, String uuid, String ip, String name, String reason, String actor) {
        try {
            long now = System.currentTimeMillis();
            database.db().insert(BanLogs.TABLE)
                    .set(BanLogs.ACTION, action)
                    .set(BanLogs.USER_ID, userId)
                    .set(BanLogs.UUID, uuid)
                    .set(BanLogs.IP, ip)
                    .set(BanLogs.NAME, name)
                    .set(BanLogs.ACTOR, (actor == null || actor.isBlank()) ? "SERVER" : actor.trim())
                    .set(BanLogs.REASON, (reason == null || reason.isBlank()) ? "No reason provided" : reason.trim())
                    .set(BanLogs.CREATED_AT, now)
                    .execute();
        } catch (Exception e) {
            Log.err("Failed to log ban event", e);
        }
    }

    public void logKick(String uuid, String ip, String name, String reason, String actor) {
        try {
            long now = System.currentTimeMillis();
            database.db().insert(KickLogs.TABLE)
                    .set(KickLogs.UUID, uuid)
                    .set(KickLogs.IP, ip)
                    .set(KickLogs.NAME, name)
                    .set(KickLogs.ACTOR, (actor == null || actor.isBlank()) ? "SERVER" : actor.trim())
                    .set(KickLogs.REASON, (reason == null || reason.isBlank()) ? "No reason provided" : reason.trim())
                    .set(KickLogs.CREATED_AT, now)
                    .execute();
        } catch (Exception e) {
            Log.err("Failed to log kick event", e);
        }
    }

    @Listener
    public void onPlayerKick(PlayerKickEvent event) {
        String name = event.name;
        if (name == null && Vars.netServer != null && Vars.netServer.admins != null) {
            if (event.uuid != null) {
                var info = Vars.netServer.admins.getInfoOptional(event.uuid);
                if (info != null) {
                    name = info.lastName;
                }
            }
            if (name == null && event.address != null) {
                var info = Vars.netServer.admins.findByIP(event.address);
                if (info != null) {
                    name = info.lastName;
                }
            }
        }

        String reason = event.reason != null ? event.reason : "unknown";
        String actor = event.actor != null ? event.actor : "SERVER";
        logKick(event.uuid, event.address, name, reason, actor);
    }

    @Listener
    public void onKickEvent(KickEvent event) {
        String name = null;
        if (Vars.netServer != null && Vars.netServer.admins != null) {
            if (event.uuid != null) {
                var info = Vars.netServer.admins.getInfoOptional(event.uuid);
                if (info != null) {
                    name = info.lastName;
                }
            }
            if (name == null && event.address != null) {
                var info = Vars.netServer.admins.findByIP(event.address);
                if (info != null) {
                    name = info.lastName;
                }
            }
        }

        String reason = event.reason != null ? event.reason : "unknown";
        String actor = "vote".equalsIgnoreCase(reason) ? "VOTE" : "SERVER";
        logKick(event.uuid, event.address, name, reason, actor);
    }

    @Listener
    public void onPlayerBan(PlayerBanEvent event) {
        String name = null;
        String ip = null;
        String uuid = event.uuid;

        if (event.player != null) {
            name = Strings.stripColors(event.player.name);
            ip = event.player.ip();
            uuid = event.player.uuid();
        } else if (Vars.netServer != null && Vars.netServer.admins != null && uuid != null) {
            var info = Vars.netServer.admins.getInfoOptional(uuid);
            if (info != null) {
                name = info.lastName;
                ip = info.lastIP;
            }
        }

        logBan("BAN", null, uuid, ip, name, "Mindustry player ban", "SERVER");
    }
}
