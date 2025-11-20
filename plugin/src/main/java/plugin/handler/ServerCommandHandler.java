package plugin.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import arc.util.CommandHandler.CommandResponse;
import arc.util.Log;
import arc.func.Cons;
import arc.util.CommandHandler;
import lombok.Getter;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Packets.KickReason;
import plugin.type.PrevCommand;

public class ServerCommandHandler {

    private static final List<String> registeredCommands = new ArrayList<>();

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

    private static void register(String text, String params, String description, Cons<String[]> runner) {
        handler.register(text, params, description, runner);
        registeredCommands.add(text);
    }

    public static void unload() {
        registeredCommands.forEach(command -> handler.removeCommand(command));

        Log.info("Server command unloaded");
    }

    public static void registerCommands(CommandHandler handler) {
        ServerCommandHandler.handler = handler;

        register("js", "<script...>", "Run arbitrary Javascript.", arg -> {
            Log.info("&fi&lw&fb" + Vars.mods.getScripts().runConsole(arg[0]));
        });

        register("say", "<message...>", "Send a message to all players.", arg -> {
            if (!Vars.state.isGame()) {
                Log.err("Not hosting. Host a game first.");
                return;
            }

            Call.sendMessage("[]" + arg[0]);

            Log.info("&fi&lcServer: &fr@", "&lw" + arg[0]);
        });

        register("kickWithReason", "<id> <message...>", "Kick player.", arg -> {
            if (!Vars.state.isGame()) {
                Log.err("Not hosting. Host a game first.");
                return;
            }

            if (arg.length == 0) {
                Log.err("Invalid usage. Specify a player ID.");
                return;
            }

            String uuid = "";
            String reason = "";

            if (arg.length == 1) {
                uuid = arg[0];
            } else {
                uuid = arg[0];
                reason = arg[1];
            }

            Player target = Groups.player.find(p -> p.uuid().equals(arg[0]));

            if (target != null) {
                if (reason.trim().isEmpty()) {
                    target.kick(KickReason.kick);
                } else {
                    target.kick(reason);
                }
                Call.sendMessage("[scarlet]" + target.name() + "[scarlet] has been kicked by the server.");
                Log.info("It is done.");
            } else {
                Log.info("Nobody with that uuid could be found: " + uuid);
            }
        });

        prevCommands.forEach(prev -> prev.getCallback().accept(handler.handleMessage(prev.getCommand())));
    }
}
