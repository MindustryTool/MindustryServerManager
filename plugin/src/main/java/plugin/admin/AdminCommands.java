package plugin.admin;

import java.util.List;

import arc.graphics.Color;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.gen.Call;
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

    @ClientCommand(name = "effect", description = "Execute effect")
    public void effect(Session session, @Param(name = "name", required = false) String name) throws Exception {
        if (name == null) {
            var fields = List.of(Fx.class.getDeclaredFields()).stream()
                    .filter(field -> {
                        try {
                            return field.get(null) instanceof Effect;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(field -> field.getName())
                    .toList();

            session.player.sendMessage("Available effects: " + Strings.join("\n", fields));
            return;
        }

        var field = Fx.class.getField(name);
        var value = field.get(null);

        if (value != null && value instanceof Effect effect) {
            Call.effect(effect, session.player.x, session.player.y, 0, Color.white);
        }
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
