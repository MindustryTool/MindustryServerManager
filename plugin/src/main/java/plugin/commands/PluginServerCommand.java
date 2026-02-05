package plugin.commands;

import arc.util.CommandHandler;
import arc.util.Log;
import plugin.Tasks;

public abstract class PluginServerCommand extends PluginCommand {
    public abstract void handle();

    public void register(CommandHandler handler) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }

        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException("description");
        }

        StringBuilder paramText = new StringBuilder("");

        for (Param param : params) {
            paramText.append(param.toParamText()).append(" ");
        }

        handler.register(name, paramText.toString(), description, (args) -> {
            Tasks.io("Server command", () -> {
                try {
                    var copy = this.getClass().getDeclaredConstructor().newInstance();
                    Param.parse(params, args);
                    copy.handle();
                } catch (ParamException e) {
                    Log.err(e.getMessage());
                } catch (Exception e) {
                    Log.err("Failed to execute command " + name, e);
                }
            });
        });
    }
}
