package plugin.commands.server;

import arc.util.Log;
import mindustry.Vars;
import plugin.Component;
import plugin.commands.PluginServerCommand;

@Component
public class JsCommand extends PluginServerCommand {
    private Param scriptParam;

    public JsCommand() {
        setName("js");
        setDescription("Run arbitrary Javascript.");
        scriptParam = variadic("script");
    }

    @Override
    public void handle() {
        try {
            Log.info("&fi&lw&fb" + Vars.mods.getScripts().runConsole(scriptParam.asString()));
        } catch (Exception e) {
            Log.err(e);
        }
    }
}
