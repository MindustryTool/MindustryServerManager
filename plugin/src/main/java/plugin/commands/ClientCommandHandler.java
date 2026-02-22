package plugin.commands;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import arc.util.CommandHandler;
import arc.util.Log;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Player;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.service.I18n;
import plugin.service.SessionHandler;
import plugin.utils.CommandUtils;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class ClientCommandHandler {

    private final SessionHandler sessionHandler;

    @Getter
    private final List<PluginClientCommand> commands = new ArrayList<>();

    @Getter
    private CommandHandler handler;

    public void registerCommands(CommandHandler handler) {
        this.handler = handler;

        for (PluginClientCommand command : commands) {
            command.register(handler);
        }
    }

    public void addCommand(ClientCommand command, Method method, Object object) {
        var name = command.name();
        var description = command.description();

        var admin = command.admin();

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }

        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException("description");
        }

        Log.info("[gray]Register client command: " + name);

        description = (admin ? "[scarlet]ADMIN[] - " : "") + description;

        commands.add(
                new PluginClientCommand(name, description, admin, method, object));
    }

    @Destroy
    public void destroy() {
        if (handler != null) {
            commands.forEach(command -> handler.removeCommand(command.getName()));
        }
        commands.clear();
        handler = null;
    }

    @Getter
    @RequiredArgsConstructor
    public class PluginClientCommand {
        private final String name;
        private final String description;
        private final boolean admin;
        private final Method method;
        private final Object object;

        public void register(CommandHandler handler) {
            handler.register(name, CommandUtils.toParamText(method), description, this::handle);
        }

        public void handle(String[] args, Player player) {
            if (admin && !player.admin) {
                player.sendMessage(I18n.t(Utils.parseLocale(player.locale()), "[scarlet]",
                        "@You must be admin to use this command."));
                return;
            }

            var session = sessionHandler.get(player).orElse(null);

            if (session == null) {
                Log.info("[scarlet]Failed to get session for player.");
                player.sendMessage(I18n.t(Utils.parseLocale(player.locale()), "[scarlet]",
                        "@Failed to get session for player."));
                Thread.dumpStack();
                return;
            }

            try {
                if (method.getParameterCount() > 0) {
                    var params = CommandUtils.mapParams(method, args, session);
                    method.invoke(object, params);
                } else {
                    method.invoke(object);
                }
            } catch (ParamException e) {
                session.player.sendMessage(I18n.t(
                        session.locale, "[scarlet]", "@Error: ", e.getMessage()));
            } catch (Exception e) {
                session.player.sendMessage(I18n.t(
                        session.locale, "[scarlet]", "@Error"));
                Log.err("Failed to execute command " + name, e);
            }
        }
    }
}
