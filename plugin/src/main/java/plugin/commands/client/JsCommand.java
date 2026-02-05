package plugin.commands.client;

import mindustry.Vars;
import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.type.Session;

@Component
public class JsCommand extends PluginClientCommand {
    private Param codeParam;

    public JsCommand() {
        setName("js");
        setDescription("Execute JavaScript code.");

        codeParam = variadic("code");
    }

    @Override
    public void handle(Session session) {
        String output = Vars.mods.getScripts().runConsole(codeParam.asString());
        session.player.sendMessage(codeParam.asString());
        session.player.sendMessage("> " + (isError(output) ? "[#ff341c]" + output : output));
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
