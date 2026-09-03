package plugin.core;

import arc.util.Log;
import plugin.annotations.*;
import plugin.commands.ClientCommandHandler;
import plugin.commands.ServerCommandHandler;

public final class PluginBootstrap {

    private PluginBootstrap() {
    }

    public static void initialize() {
        Registry.setLogger(new RegistryLogger() {
            @Override
            public void debug(String message, Object... args) {
                Log.debug(message, args);
            }

            @Override
            public void info(String message, Object... args) {
                Log.info(message, args);
            }

            @Override
            public void error(String message, Throwable throwable, Object... args) {
                Log.err(message, args);
                if (throwable != null) {
                    Log.err(throwable);
                }
            }
        });

        Registry.registerClassHandler(Configuration.class, (annotation, instance) ->
                Registry.get(ConfigManager.class).process(annotation, instance));

        Registry.registerFieldHandler(Persistence.class, (annotation, field, instance) ->
                Registry.get(PersistenceManager.class).load(field, annotation, instance));

        Registry.registerMethodHandler(Schedule.class, (annotation, method, instance) ->
                Registry.get(Scheduler.class).process(annotation, instance, method));

        Registry.registerMethodHandler(Listener.class, (annotation, method, instance) ->
                Registry.get(EventRegistrar.class).register(annotation, instance, method));

        Registry.registerMethodHandler(Trigger.class, (annotation, method, instance) ->
                Registry.get(EventRegistrar.class).register(annotation, instance, method));

        Registry.registerMethodHandler(FileWatcher.class, (annotation, method, instance) ->
                Registry.get(FileWatcherManager.class).process(annotation, instance, method));

        Registry.registerMethodHandler(ClientCommand.class, (annotation, method, instance) ->
                Registry.get(ClientCommandHandler.class).addCommand(annotation, method, instance));

        Registry.registerMethodHandler(ServerCommand.class, (annotation, method, instance) ->
                Registry.get(ServerCommandHandler.class).addCommand(annotation, method, instance));

        Registry.registerMethodHandler(PlayerActionFilter.class, (annotation, method, instance) ->
                Registry.get(ActionFilterManager.class).addFilter(annotation, method, instance));
    }
}
