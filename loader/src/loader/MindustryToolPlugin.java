package loader;

import arc.util.CommandHandler;

public interface MindustryToolPlugin {

    public void init();

    public void registerServerCommands(CommandHandler handler);

    public void registerClientCommands(CommandHandler handler);

    public void unload();

    public default void onEvent(Object event) {
    }
}
