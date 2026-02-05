package plugin.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import arc.util.CommandHandler.CommandResponse;
import arc.util.Log;
import arc.struct.Seq;
import arc.util.CommandHandler;
import lombok.Getter;
import plugin.Component;
import plugin.IComponent;
import plugin.PluginEvents;
import plugin.Registry;
import plugin.event.PluginUnloadEvent;
import plugin.type.PrevCommand;

@Component
public class ServerCommandHandler implements IComponent {

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
            Log.info("Server command registered: " + command.getName());
        }

        prevCommands.forEach(prev -> prev.getCallback().accept(handler.handleMessage(prev.getCommand())));
        prevCommands.clear();

        PluginEvents.run(PluginUnloadEvent.class, this::unload);
    }

    private void unload() {
        if (handler != null) {
            commands.forEach(command -> handler.removeCommand(command.getName()));
        }
        commands.clear();

        handler = null;

        Log.info("Server command unloaded");
    }

    @Override
    public void init() {
    }

    @Override
    public void destroy() {
        unload();
    }

    public Seq<arc.util.CommandHandler.Command> getCommandList() {
        return handler == null ? new Seq<>() : handler.getCommandList();
    }
}
