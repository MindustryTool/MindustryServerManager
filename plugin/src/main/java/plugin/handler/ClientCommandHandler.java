package plugin.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import arc.util.CommandHandler;
import arc.util.Log;
import lombok.Getter;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.ServerController;
import plugin.commands.PluginCommand;
import plugin.commands.client.HubCommand;
import plugin.commands.client.JsCommand;
import plugin.commands.client.LoginCommand;
import plugin.commands.client.MapsCommand;
import plugin.commands.client.RedirectCommand;
import plugin.commands.client.RtvCommand;
import plugin.commands.client.ServersCommand;
import plugin.commands.client.VnwCommand;
import plugin.type.HudOption;
import plugin.type.PaginationRequest;
import plugin.type.PlayerPressCallback;

public class ClientCommandHandler {

    private static final List<PluginCommand> commands = new ArrayList<>();

    @Getter
    private static CommandHandler handler;

    public static void unload() {
        commands.forEach(command -> handler.removeCommand(command.getName()));
        commands.clear();
        Log.info("Client command unloaded");
    }

    public static void registerCommands(CommandHandler handler) {
        ClientCommandHandler.handler = handler;

        commands.add(new RtvCommand());
        commands.add(new MapsCommand());
        commands.add(new ServersCommand());
        commands.add(new HubCommand());
        commands.add(new JsCommand());
        commands.add(new LoginCommand());
        commands.add(new VnwCommand());
        commands.add(new RedirectCommand());

        for (PluginCommand command : commands) {
            command.register(handler, true);
        }
    }

    public static void onServerChoose(Player player, String id, String name) {
        HudHandler.closeFollowDisplay(player, HudHandler.SERVERS_UI);
        player.sendMessage(
                String.format("[green]Starting server [white]%s, [white]redirection will happen soon", name));

        try {
            ServerController.backgroundTask(() -> {
                var data = ApiGateway.host(id);
                player.sendMessage("[green]Redirecting");
                Call.sendMessage(
                        String.format("%s [green]redirecting to server [white]%s, use [green]/servers[white] to follow",
                                player.coloredName(), name));

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
            player.sendMessage("Error: Can not load server");
            e.printStackTrace();
        }
    }

    public static void sendRedirectServerList(Player player, int page) {
        ServerController.backgroundTask(() -> {
            try {
                var size = 8;
                var request = new PaginationRequest()//
                        .setPage(page)//
                        .setSize(size);

                var servers = ApiGateway.getServers(request);

                PlayerPressCallback invalid = (p, s) -> {
                    Call.infoToast(p.con, "Please don't click there", 10f);
                    sendRedirectServerList(p, (int) s);
                };

                List<List<HudOption>> options = new ArrayList<>(Arrays.asList(
                        Arrays.asList(HudHandler.option(invalid, "[#FFD700]Server name"),
                                HudHandler.option(invalid, "[#FFD700]Players playing")),
                        Arrays.asList(HudHandler.option(invalid, "[#87CEEB]Server Gamemode"),
                                HudHandler.option(invalid, "[#FFA500]Map Playing")),
                        Arrays.asList(HudHandler.option(invalid, "[#DA70D6]Server Mods")),
                        Arrays.asList(HudHandler.option(invalid, "[#B0B0B0]Server Description"))));

                servers.forEach(server -> {
                    PlayerPressCallback valid = (p, s) -> //
                    onServerChoose(p, server.getId().toString(), server.getName());

                    options.add(Arrays.asList(HudHandler.option(invalid, "-----------------")));
                    options.add(Arrays.asList(HudHandler.option(valid, String.format("[#FFD700]%s", server.getName())),
                            HudHandler.option(valid, String.format("[#32CD32]Players: %d", server.getPlayers()))));
                    options.add(Arrays.asList(
                            HudHandler.option(valid, String.format("[#87CEEB]Gamemode: %s", server.getMode())),
                            HudHandler.option(valid, String.format("[#1E90FF]Map: %s",
                                    server.getMapName() != null ? server.getMapName() : "[#FF4500]Server offline"))));

                    if (server.getMods() != null && !server.getMods().isEmpty()) {
                        options.add(Arrays.asList(HudHandler.option(valid,
                                String.format("[#DA70D6]Mods: %s", String.join(", ", server.getMods())))));
                    }

                    if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                        options.add(
                                Arrays.asList(
                                        HudHandler.option(valid,
                                                String.format("[#B0B0B0]%s", server.getDescription()))));
                    }

                });

                options.add(Arrays.asList(//
                        page > 0//
                                ? HudHandler.option((p, state) -> {
                                    sendRedirectServerList(player, (int) state - 1);
                                    HudHandler.closeFollowDisplay(p, HudHandler.SERVERS_UI);
                                }, "[yellow]Previous")
                                : HudHandler.option(invalid, "First page"), //
                        servers.size() == size//
                                ? HudHandler.option((p, state) -> {
                                    sendRedirectServerList(player, (int) state + 1);
                                    HudHandler.closeFollowDisplay(p, HudHandler.SERVERS_UI);
                                }, "[green]Next")
                                : HudHandler.option(invalid, "No more")));

                options.add(Arrays.asList(HudHandler.option(
                        (p, state) -> HudHandler.closeFollowDisplay(p, HudHandler.SERVERS_UI),
                        "[red]Close")));

                HudHandler.showFollowDisplays(player, HudHandler.SERVERS_UI, "Servers", "",
                        Integer.valueOf(page), options);
            } catch (Exception e) {
                Log.err(e);
            }
        });
    }

}
