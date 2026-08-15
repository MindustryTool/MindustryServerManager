package plugin.admin;

import mindustry.Vars;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.session.Session;

@Component
public class AdminCommands {

    @ClientCommand(name = "js", description = "Execute JavaScript code")
    public void js(Session session, @Param(name = "code", variadic = true) String[] code) {
        String js = String.join(" ", code);
        String output = Vars.mods.getScripts().runConsole(js);
        session.player.sendMessage(js);
        session.player.sendMessage("> " + (isJsError(output) ? "[#ff341c]" + output : output));
    }

    private boolean isJsError(String output) {
        try {
            String errorName = output.substring(0, output.indexOf(' ') - 1);
            Class.forName("org.mozilla.javascript." + errorName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}