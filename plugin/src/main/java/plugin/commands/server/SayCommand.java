package plugin.commands.server;

import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Call;
import plugin.Component;
import plugin.commands.PluginCommand;

@Component
public class SayCommand extends PluginCommand {
    private Param messageParam;

    public SayCommand() {
        setName("say");
        setDescription("Send a message to all players.");
        messageParam = variadic("message");
    }

    @Override
    public void handleServer() {
        if (!Vars.state.isGame()) {
            Log.err("Not hosting. Host a game first.");
            return;
        }

        Call.sendMessage("[]" + messageParam.asString());
        Log.info("&fi&lcServer: &fr@", "&lw" + messageParam.asString());
    }
}
