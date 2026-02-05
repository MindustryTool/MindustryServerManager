package plugin.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import arc.util.CommandHandler.CommandResponse;
import arc.struct.Seq;
import arc.util.CommandHandler;
import lombok.Getter;
import plugin.Registry;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.type.PrevCommand;

@Component
public class ServerCommandHandler {

    private final List<PluginServerCommand> commands = new ArrayList<>();

    @Getter
    private CommandHandler handler;

    private final List<PrevCommand> prevCommands = new ArrayList<>();

    public void execute(String command, Consumer<CommandResponse> callback) {
        if (handler == null) {
            prevCommands.add(new PrevCommand(command, callback));
        } else {
            callback.accept(handler.handleMessage(command));
        }
    }

    public void registerCommands(CommandHandler handler) {
        this.handler = handler;

        List<PluginServerCommand> registeredCommands = Registry.getAll(PluginServerCommand.class);
        commands.addAll(registeredCommands);

        for (PluginServerCommand command : commands) {
            command.register(handler);
        }

        prevCommands.forEach(prev -> prev.getCallback().accept(handler.handleMessage(prev.getCommand())));
        prevCommands.clear();
    }

    @Destroy
    public void destroy() {
        if (handler != null) {
            commands.forEach(command -> handler.removeCommand(command.getName()));
        }
        commands.clear();

        handler = null;
    }

    public Seq<arc.util.CommandHandler.Command> getCommandList() {
        return handler == null ? new Seq<>() : handler.getCommandList();
    }
}
