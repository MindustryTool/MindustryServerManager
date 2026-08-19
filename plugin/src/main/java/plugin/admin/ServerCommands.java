package plugin.admin;

import arc.Core;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Packets.KickReason;
import plugin.update.PluginUpdater;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.annotations.ServerCommand;
import plugin.core.Registry;
import plugin.database.Database;
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
        Core.settings.put(Registry.GAMEMODE_KEY, gamemode);
        Core.settings.forceSave();

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
        
        database.prepare(sql, statement -> {
            boolean hasResultSet = statement.execute();

            if (hasResultSet) {
                try (var result = statement.getResultSet()) {
                    var metaData = result.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    StringBuilder header = new StringBuilder("[sky]");
                    for (int i = 1; i <= columnCount; i++) {
                        header.append(metaData.getColumnName(i)).append(" | ");
                    }

                    Log.info(header.toString());

                    while (result.next()) {
                        StringBuilder row = new StringBuilder("[sky]");
                        for (int i = 1; i <= columnCount; i++) {
                            row.append(result.getString(i)).append(" | ");
                        }
                        Log.info(row.toString());
                    }
                }
            } else {
                int updateCount = statement.getUpdateCount();
                Log.info("Query OK, " + updateCount + " rows affected.");
            }
        });
    }
}
