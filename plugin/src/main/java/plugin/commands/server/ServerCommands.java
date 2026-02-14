package plugin.commands.server;

import arc.Core;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Packets.KickReason;
import plugin.PluginUpdater;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.annotations.ServerCommand;
import plugin.core.Registry;
import plugin.database.DB;
import plugin.service.I18n;
import plugin.utils.Utils;

@Component
public class ServerCommands {

    @ServerCommand(name = "gamemode", description = "Set gamemode")
    private void gamemode(@Param(name = "gamemode") String gamemode) {
        Core.settings.put(Registry.GAMEMODE_KEY, gamemode);
        Core.settings.forceSave();

        Log.info("[purple]Set gamemode to: " + gamemode);
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
                String msg = I18n.t(locale, "[scarlet]", target.name(), "[scarlet]", " ",
                        "@has been kicked by the server.");
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
        Call.sendMessage("[cyan]Server scheduled for a restart.");
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
        
        DB.prepare(sql, statement -> {
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
