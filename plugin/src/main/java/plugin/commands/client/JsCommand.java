package plugin.commands.client;

import mindustry.Vars;
import mindustry.gen.Player;
import plugin.commands.PluginCommand;

public class JsCommand extends PluginCommand {
    private Param codeParam;

    public JsCommand() {
        setName("js");
        setDescription("Execute JavaScript code.");
        codeParam = variadic("code");
    }

    @Override
    public void handleClient(Player player) {
        if (player.admin) {
            String output = Vars.mods.getScripts().runConsole(codeParam.asString());
            player.sendMessage("> " + (isError(output) ? "[#ff341c]" + output : output));
        } else {
            player.sendMessage("[scarlet]You must be admin to use this command.");
        }
    }

    private boolean isError(String output) {
        try {
            String errorName = output.substring(0, output.indexOf(' ') - 1);
            Class.forName("org.mozilla.javascript." + errorName);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
