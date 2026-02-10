package plugin.commands.server;

import arc.Core;
import arc.util.Log;
import plugin.commands.PluginServerCommand;
import plugin.core.Registry;

public class GamemodeCommand extends PluginServerCommand {

    Param param;

    public GamemodeCommand() {
        setName("gamemode");
        setDescription("Set gamemode.");

        param = required("gamemode");
    }

    @Override
    public void handle() {
        Core.settings.put(Registry.GAMEMODE_KEY, param.asString());
        Log.info("Set gamemode to: " + param.asString());
    }
}
