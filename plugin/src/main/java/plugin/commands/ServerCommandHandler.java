package plugin.commands;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import arc.util.CommandHandler.CommandResponse;
import arc.util.Log;
import arc.struct.Seq;
import arc.util.CommandHandler;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.ServerCommand;
import plugin.type.PrevCommand;
import plugin.utils.CommandUtils;

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

        for (PluginServerCommand command : commands) {
            command.register(handler);
        }

        prevCommands.forEach(prev -> prev.getCallback().accept(handler.handleMessage(prev.getCommand())));
        prevCommands.clear();
    }

    public void addCommand(ServerCommand command, Method method, Object object) {
        var name = command.name();
        var description = command.description();

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }

        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException("description");
        }

        Log.info("[gray]Register server command: " + name);
        var cmd = new PluginServerCommand(name, description, method, object);
        commands.add(cmd);

        if (handler != null) {
            cmd.register(handler);
        }

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

    @Getter
    @RequiredArgsConstructor
    public class PluginServerCommand {
        private final String name;
        private final String description;
        private final Method method;
        private final Object object;

        public void register(CommandHandler handler) {
            handler.register(name, CommandUtils.toParamText(method), description, this::handle);
        }

        public void handle(String[] args) {

            try {
                if (method.getParameterCount() > 0) {
                    var params = CommandUtils.mapParams(method, args, null);
                    method.invoke(object, params);
                } else {
                    method.invoke(object);
                }
            } catch (Exception e) {
                Log.err("Failed to execute command " + name, e);
            }
        }
    }
}
