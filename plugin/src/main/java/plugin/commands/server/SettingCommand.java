package plugin.commands.server;

import arc.Core;
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
        Core.settings.put(key.asString(), value.asString());
    }
}
