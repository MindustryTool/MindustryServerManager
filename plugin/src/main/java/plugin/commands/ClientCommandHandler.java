package plugin.commands;

import java.util.ArrayList;
import java.util.List;

import arc.util.CommandHandler;
import arc.util.Log;
import lombok.Getter;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import plugin.PluginEvents;
import plugin.Control;
import plugin.commands.client.DiscordCommand;
import plugin.commands.client.GriefCommand;
import plugin.commands.client.HubCommand;
import plugin.commands.client.JsCommand;
import plugin.commands.client.LoginCommand;
import plugin.commands.client.MapCommand;
import plugin.commands.client.MeCommand;
import plugin.commands.client.PlayerInfoCommand;
import plugin.commands.client.RankCommand;
import plugin.commands.client.RedirectCommand;
import plugin.commands.client.RtvCommand;
import plugin.commands.client.ServersCommand;
import plugin.commands.client.TrailCommand;
import plugin.commands.client.VnwCommand;
import plugin.commands.client.WebsiteCommand;
import plugin.commands.client.SubmitMapCommand;
import plugin.commands.client.AdminCommand;
import plugin.event.PluginUnloadEvent;
import plugin.handler.ApiGateway;
import plugin.handler.I18n;
import plugin.menus.GlobalServerListMenu;
import plugin.type.Session;
import plugin.utils.Utils;

public class ClientCommandHandler {

    private static final List<PluginCommand> commands = new ArrayList<>();

    @Getter
    private static CommandHandler handler;

    public static void registerCommands(CommandHandler handler) {
        ClientCommandHandler.handler = handler;

        commands.add(new RtvCommand());
        commands.add(new ServersCommand());
        commands.add(new HubCommand());
        commands.add(new JsCommand());
        commands.add(new LoginCommand());
        commands.add(new VnwCommand());
        commands.add(new RedirectCommand());
        commands.add(new PlayerInfoCommand());
        commands.add(new MeCommand());
        commands.add(new GriefCommand());
        commands.add(new TrailCommand());
        commands.add(new MapCommand());
        commands.add(new SubmitMapCommand());
        commands.add(new DiscordCommand());
        commands.add(new AdminCommand());
        commands.add(new RankCommand());
        commands.add(new WebsiteCommand());

        for (PluginCommand command : commands) {
            command.register(handler, true);
            Log.info("Client command registered: " + command.getName());
        }

        PluginEvents.run(PluginUnloadEvent.class, ClientCommandHandler::unload);
    }

    private static void unload() {
        commands.forEach(command -> handler.removeCommand(command.getName()));
        commands.clear();

        handler = null;

        Log.info("Client command unloaded");
    }

    public static void onServerChoose(Session session, String id, String name) {
        session.player.sendMessage(I18n.t(session.locale,
                "[green]", "@Starting server ", "[white]", name, ", ", "[white]", "@redirection will happen soon"));

        try {
            Control.ioTask("Redirect Server", () -> {
                var data = ApiGateway.host(id);
                session.player.sendMessage(I18n.t(session.locale,
                        "[green]", "@Redirecting"));
                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = I18n.t(locale, session.player.coloredName(), " ", "[green]",
                            "@redirecting to server ", "[white]", name,
                            ", ", "@use ", "[green]", "/servers", "[white]", " ", "@to follow");
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

    public static void sendRedirectServerList(Session session, int page) {
        new GlobalServerListMenu().send(session, page);
    }

}
