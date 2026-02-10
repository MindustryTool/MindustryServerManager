package plugin.commands.server;

import arc.Core;
import arc.util.Log;
import plugin.annotations.Component;
import plugin.commands.PluginServerCommand;

@Component
public class SettingCommand extends PluginServerCommand {

    Param key;
    Param value;

    public SettingCommand() {
        setName("setting");
        setDescription("Set setting");
        key = required("key");
        value = optional("value");
    }

    @Override
    public void handle() {
        if (value.hasValue()) {
            Core.settings.put(key.asString(), value.asString());
            Log.info("Setting @ to @", key.asString(), value.asString());
        } else {
            Core.settings.remove(key.asString());
            Log.info("Setting @ removed", key.asString());
        }

        Core.settings.forceSave();
    }
}
