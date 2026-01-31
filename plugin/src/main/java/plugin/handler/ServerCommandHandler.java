package plugin.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import arc.util.CommandHandler.CommandResponse;
import arc.util.Log;
import arc.util.CommandHandler;
import lombok.Getter;
import plugin.commands.PluginCommand;
import plugin.commands.server.JsCommand;
import plugin.commands.server.KickWithReasonCommand;
import plugin.commands.server.SayCommand;
import plugin.type.PrevCommand;

public class ServerCommandHandler {

    private static final List<PluginCommand> commands = new ArrayList<>();

    @Getter
    private static CommandHandler handler;

    private static final List<PrevCommand> prevCommands = new ArrayList<>();

    public static void execute(String command, Consumer<CommandResponse> callback) {
        if (handler == null) {
            prevCommands.add(new PrevCommand(command, callback));
        } else {
            callback.accept(handler.handleMessage(command));
        }
    }

    public static void registerCommands(CommandHandler handler) {
        ServerCommandHandler.handler = handler;

        commands.add(new JsCommand());
        commands.add(new SayCommand());
        commands.add(new KickWithReasonCommand());

        for (PluginCommand command : commands) {
            command.register(handler, false);
            Log.info("Server command registered: " + command.getName());
        }

        prevCommands.forEach(prev -> prev.getCallback().accept(handler.handleMessage(prev.getCommand())));
        prevCommands.clear();
    }

    public static void unload() {
        commands.forEach(command -> handler.removeCommand(command.getName()));
        commands.clear();

        handler = null;

        Log.info("Server command unloaded");
    }
}
