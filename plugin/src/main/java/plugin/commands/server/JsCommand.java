package plugin.commands.server;

import arc.util.Log;
import mindustry.Vars;
import plugin.commands.PluginCommand;

public class JsCommand extends PluginCommand {
    private Param scriptParam;

    public JsCommand() {
        setName("js");
        setDescription("Run arbitrary Javascript.");
        scriptParam = variadic("script");
    }

    @Override
    public void handleServer() {
        try {
            Log.info("&fi&lw&fb" + Vars.mods.getScripts().runConsole(scriptParam.asString()));
        } catch (Exception e) {
            Log.err(e);
        }
    }
}
