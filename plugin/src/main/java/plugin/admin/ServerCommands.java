package plugin.admin;

import java.time.Instant;
import java.util.Set;

import arc.Core;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;
import mindustry.net.Packets.KickReason;
import plugin.update.PluginUpdater;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.annotations.ServerCommand;
import plugin.database.Database;
import plugin.gamemode.Gamemode;
import plugin.security.UserBanService;
import plugin.session.ExpUtils;
import plugin.session.Session;
import plugin.session.SessionData;
import plugin.session.SessionRepository;
import plugin.session.SessionService;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
public class ServerCommands {
    private final Database database;

    public ServerCommands(Database database) {
        this.database = database;
    }

    @ServerCommand(name = "gamemode", description = "Set gamemode")
    private void gamemode(@Param(name = "gamemode") String gamemode) {
        Gamemode.set(gamemode);

        Log.info("[sky]Set gamemode to: " + gamemode);
    }

    @ServerCommand(name = "js", description = "Run arbitrary Javascript")
    private void js(@Param(name = "script", variadic = true) String[] script) {
        try {
            Log.info("&fi&lw&fb" + Vars.mods.getScripts().runConsole(String.join(" ", script)));
        } catch (Exception e) {
            Log.err(e);
        }
    }

    @ServerCommand(name = "kickWithReason", description = "Kick player")
    private void kickWithReason(@Param(name = "id") String id,
            @Param(name = "duration") String duration,
            @Param(name = "message", variadic = true) String[] reasons) {
        if (!Vars.state.isGame()) {
            Log.err("Not hosting. Host a game first.");
            return;
        }

        var reason = String.join(" ", reasons);

        Player target = Groups.player.find(p -> p.uuid().equals(id));

        if (target != null) {
            if (reason == null || reason.trim().isEmpty()) {
                target.kick(KickReason.kick);
            } else {
                target.kick(reason);
            }
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = Tr.t(locale, "admin.kicked_by_server", "player", target.name());
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            Log.info("It is done.");
        } else {
            Log.info("Nobody with that uuid could be found: " + id);
        }
    }

    @ServerCommand(name = "restart", description = "Restart the server")
    private void restart(PluginUpdater updater) {
        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = Tr.t(locale, "admin.restart_scheduled");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });
        updater.scheduleRestart();
    }

    @ServerCommand(name = "say", description = "Send a message to all players")
    private void say(@Param(name = "message", variadic = true) String[] messages) {
        if (!Vars.state.isGame()) {
            Log.err("Not hosting. Host a game first.");
            return;
        }

        var message = String.join(" ", messages);

        Call.sendMessage("[white]" + message);
        Log.info("&fi&lcServer: &fr@", "&lw" + message);
    }

    @ServerCommand(name = "setting", description = "Set setting")
    private void setting(@Param(name = "key") String key, @Param(name = "value", required = false) String value) {
        if (value != null) {
            Core.settings.put(key, value);
            Log.info("Setting @ to @", key, value);
        } else {
            Core.settings.remove(key);
            Log.info("Setting @ removed", key);
        }

        Core.settings.forceSave();
    }

    @ServerCommand(name = "sql", description = "Run SQL script")
    private void sql(@Param(name = "script", variadic = true) String[] code) {
        var sql = String.join(" ", code);

        var result = database.db().rawExecute(sql);

        if (result.hasRows()) {
            var rows = result.rows();

            StringBuilder header = new StringBuilder("[sky]");
            for (var column : rows.get(0).columnNames()) {
                header.append(column).append(" | ");
            }

            Log.info(header.toString());

            for (var row : rows) {
                StringBuilder line = new StringBuilder("[sky]");
                for (var column : row.columnNames()) {
                    line.append(row.getObject(column)).append(" | ");
                }
                Log.info(line.toString());
            }
        } else {
            Log.info("Query OK, " + result.updateCount() + " rows affected.");
        }
    }

    @ServerCommand(name = "userban", description = "Ban an account by userId")
    private void userban(@Param(name = "userId") String userId, UserBanService banService) {
        if (banService.ban(userId)) {
            Log.info("User '@' has been banned.", userId);
        } else {
            Log.info("User '@' is already banned or invalid ID.", userId);
        }
    }

    @ServerCommand(name = "userunban", description = "Unban an account by userId")
    private void userunban(@Param(name = "userId") String userId, UserBanService banService) {
        if (banService.unban(userId)) {
            Log.info("User '@' has been unbanned.", userId);
        } else {
            Log.info("User '@' is not banned.", userId);
        }
    }

    @ServerCommand(name = "userbans", description = "List banned userIds")
    private void userbans(UserBanService banService) {
        Set<String> bans = banService.getBannedUserIds();
        if (bans.isEmpty()) {
            Log.info("No user IDs are currently banned.");
        } else {
            Log.info("Banned user IDs (@): @", bans.size(), String.join(", ", bans));
        }
    }

    @ServerCommand(name = "exp", description = "Add, remove, or set exp or level for a player uuid")
    public void exp(@Param(name = "uuid") String uuid,
            @Param(name = "amount", variadic = true) String[] amount,
            SessionService sessionService,
            SessionRepository sessionRepository) {
        if (uuid == null || uuid.trim().isEmpty()) {
            Log.err("UUID cannot be empty.");
            return;
        }

        String targetUuid = uuid.trim();
        String rawAmount = String.join("", amount).replaceAll("\\s+", "");

        Session online = sessionService.getByUuid(targetUuid).orElse(null);
        SessionData data;
        String playerName;
        boolean isOnline = online != null;

        if (isOnline) {
            data = online.getData();
            playerName = Strings.stripColors(online.player.name);
        } else {
            PlayerInfo info = (Vars.netServer != null && Vars.netServer.admins != null)
                    ? Vars.netServer.admins.getInfoOptional(targetUuid)
                    : null;
            boolean exists = sessionRepository.exists(targetUuid) || info != null;
            if (!exists) {
                Log.err("Player with uuid '@' not found.", targetUuid);
                return;
            }

            data = sessionRepository.get(targetUuid);
            if (data.name == null || data.name.isEmpty()) {
                if (info != null && info.lastName != null) {
                    data.name = info.lastName;
                }
            }
            playerName = (data.name != null && !data.name.isEmpty()) ? Strings.stripColors(data.name) : targetUuid;
            // Prevent offline time from being counted as playtime when writing session data
            data.lastSaved = Instant.now().toEpochMilli();
        }

        float oldExp;
        int oldLevel;
        synchronized (data) {
            oldExp = data.exp;
        }
        oldLevel = (isOnline && online.currentLevel > 0)
                ? online.currentLevel
                : ExpUtils.levelFromTotalExp((long) oldExp);

        float newExp;
        try {
            newExp = ExpUtils.calculateExp(oldExp, rawAmount);
        } catch (IllegalArgumentException e) {
            Log.err(e.getMessage());
            return;
        }

        synchronized (data) {
            data.exp = newExp;
        }

        int newLevel;
        if (isOnline) {
            sessionService.updateLevel(online);
            newLevel = online.currentLevel;
            sessionRepository.save(targetUuid, online.getData());
        } else {
            newLevel = ExpUtils.levelFromTotalExp((long) newExp);
            sessionRepository.save(targetUuid, data);
        }

        Log.info("Updated exp for @ (@)@: @ -> @ exp, level @ -> @",
                playerName, targetUuid, isOnline ? "" : " [offline]",
                (long) oldExp, (long) newExp, oldLevel, newLevel);
    }
}
