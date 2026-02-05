package plugin.commands;

import java.util.ArrayList;
import java.util.List;

import arc.util.CommandHandler;
import arc.util.Log;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import plugin.Component;
import plugin.IComponent;
import plugin.PluginEvents;
import plugin.Control;
import plugin.Registry;
import plugin.event.PluginUnloadEvent;
import plugin.handler.ApiGateway;
import plugin.handler.I18n;
import plugin.type.Session;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class ClientCommandHandler implements IComponent {

    private final List<PluginClientCommand> commands = new ArrayList<>();

    @Getter
    private CommandHandler handler;

    private final ApiGateway apiGateway;

    public void registerCommands(CommandHandler handler) {
        this.handler = handler;

        // Get all commands from registry
        List<PluginClientCommand> registeredCommands = Registry.getAll(PluginClientCommand.class);
        commands.addAll(registeredCommands);

        for (PluginClientCommand command : commands) {
            command.register(handler);
        }

        PluginEvents.run(PluginUnloadEvent.class, this::unload);
    }

    @Override
    public void init() {
        // Nothing to init here, wait for registerCommands
    }

    @Override
    public void destroy() {
        unload();
    }

    private void unload() {
        if (handler != null) {
            commands.forEach(command -> handler.removeCommand(command.getName()));
        }
        commands.clear();
        handler = null;
    }

    // ... onServerChoose ...
    public void onServerChoose(Session session, String id, String name) {
        session.player.sendMessage(I18n.t(session.locale,
                "[green]", "@Starting server ", "[]", name, ", ", "[]", "@redirection will happen soon"));

        try {
            Control.ioTask("Redirect Server", () -> {
                var data = apiGateway.host(id);

                session.player.sendMessage(I18n.t(session.locale, "[green]", "@Redirecting"));
                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = I18n.t(locale, session.player.coloredName(), " ", "[green]",
                            "@redirecting to server ", "[]", name, ", []", "@use ", "[accent]", "/servers", "[]",
                            " ", "@to follow");

                    for (var p : players) {
                        p.sendMessage(msg);
                    }
                });

                String host = "";
                int port = 6567;

                var colon = data.lastIndexOf(":");

                if (colon > 0) {
                    host = data.substring(0, colon);
                    port = Integer.parseInt(data.substring(colon + 1));
                } else {
                    host = data;
                }

                final var h = host;
                final var p = port;

                Groups.player.forEach(target -> {
                    Log.info("Redirecting player " + target.name + " to " + h + ":" + p);
                    Call.connect(target.con, h, p);
                });
            });
        } catch (Exception e) {
            session.player.sendMessage(I18n.t(session.locale,
                    "@Error: ", "@Can not load server"));
            e.printStackTrace();
        }
    }
}
