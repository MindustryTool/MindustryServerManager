package plugin.commands;

import arc.util.CommandHandler;
import arc.util.Log;
import lombok.Getter;
import lombok.Setter;
import mindustry.gen.Player;
import plugin.Registry;
import plugin.Tasks;
import plugin.service.I18n;
import plugin.service.SessionHandler;
import plugin.type.Session;
import plugin.utils.Utils;

public abstract class PluginClientCommand extends PluginCommand {

    @Setter
    @Getter
    private boolean admin = true;

    public abstract void handle(Session session);

    public void register(CommandHandler handler) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }

        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException("description");
        }

        description = (admin ? "[scarlet]ADMIN[] - " : "") + description;

        StringBuilder paramText = new StringBuilder("");

        for (Param param : params) {
            paramText.append(param.toParamText()).append(" ");
        }

        handler.register(name, paramText.toString(), description, (args, p) -> {
            if (p instanceof Player player) {
                if (admin && !player.admin) {
                    player.sendMessage(I18n.t(Utils.parseLocale(player.locale()), "[scarlet]",
                            "@You must be admin to use this command."));
                    return;
                }

                var session = Registry.get(SessionHandler.class).get(player).orElse(null);

                if (session == null) {
                    Log.info("[scarlet]Failed to get session for player.");
                    Thread.dumpStack();
                    return;
                }

                Tasks.io("Client command", () -> {
                    try {
                        var copy = this.getClass().getDeclaredConstructor().newInstance();
                        Param.parse(params, args);
                        copy.handle(session);
                    } catch (ParamException e) {
                        session.player.sendMessage(I18n.t(
                                session.locale, "[scarlet]", "@Error: ", e.getMessage()));
                    } catch (Exception e) {
                        session.player.sendMessage(I18n.t(
                                session.locale, "[scarlet]", "@Error"));
                        Log.err("Failed to execute command " + name, e);
                    }
                });
            } else {
                throw new IllegalArgumentException("Player expected");
            }
        });
    }
}
