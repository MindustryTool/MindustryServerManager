package plugin.commands.server;

import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Call;
import plugin.annotations.Component;
import plugin.commands.PluginServerCommand;

@Component
public class SayCommand extends PluginServerCommand {
    private Param messageParam;

    public SayCommand() {
        setName("say");
        setDescription("Send a message to all players.");
        messageParam = variadic("message");
    }

    @Override
    public void handle() {
        if (!Vars.state.isGame()) {
            Log.err("Not hosting. Host a game first.");
            return;
        }

        Call.sendMessage("[]" + messageParam.asString());
        Log.info("&fi&lcServer: &fr@", "&lw" + messageParam.asString());
    }
}
