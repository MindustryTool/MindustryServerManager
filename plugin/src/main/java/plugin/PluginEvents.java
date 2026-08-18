package plugin;

import arc.struct.Seq;
import arc.util.Log;
import mindustry.game.EventType;
import arc.struct.ObjectMap;
import arc.Events;
import arc.func.Cons;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class PluginEvents {

    /** Simple global event listener system. */
    private static final ObjectMap<Object, Seq<Cons<?>>> events = new ObjectMap<>();

    /** Handle an event by class. */
    public static <T> void on(Class<T> type, Cons<T> listener) {
        events.get(type, () -> new Seq<>(Cons.class)).add(listener);
    }

    /** Handle an event by enum trigger. */
    public static void run(Object type, Runnable listener) {
        events.get(type, () -> new Seq<>(Cons.class)).add(e -> listener.run());
    }

    /**
     * Only use this method if you have the reference to the exact listener object
     * that was used.
     */
    public static <T> boolean remove(Class<T> type, Cons<T> listener) {
        return events.get(type, () -> new Seq<>(Cons.class)).remove(listener);
    }

    /** Fires an enum trigger. */
    public static <T extends Enum<T>> void fire(Enum<T> type) {
        Seq<Cons<?>> listeners = events.get(type);
        try {
            if (listeners != null) {
                int len = listeners.size;
                Cons[] items = listeners.items;
                for (int i = 0; i < len; i++) {
                    items[i].get(type);
                }
            }
        } catch (Exception e) {
            Log.err("Failed to fire event: " + type, e);
        }
    }

    /** Fires a non-enum event by class. */
    public static <T> void fire(T type) {
        fire(type.getClass(), type);
    }

    public static <T> void fire(Class<?> ctype, T type) {
        Seq<Cons<?>> listeners = events.get(ctype);

        try {
            if (listeners != null) {
                int len = listeners.size;
                Cons[] items = listeners.items;
                for (int i = 0; i < len; i++) {
                    items[i].get(type);
                }
            }
        } catch (Exception e) {
            Log.err("Failed to fire event: " + type, e);
        }
    }

    public static void register() {
        registerEventListener();
        registerTriggerListener();
    }

    /** Don't do this. */
    public static void unregister() {
        events.clear();
    }

    
    private static void registerEventListener() {
        for (Class<?> clazz : EventType.class.getDeclaredClasses()) {
            Events.on(clazz, event -> {
                try {
                    if (Control.state != PluginState.LOADED) {
                        return;
                    }

                    PluginEvents.fire(event);

                } catch (Exception e) {
                    Log.err("Failed to invoke event @", event, e);
                    Log.err(e);
                }
            });
        }
    }

    private static void registerTriggerListener() {
        for (EventType.Trigger trigger : EventType.Trigger.values()) {
            Events.run(trigger, () -> {
                try {
                    if (Control.state != PluginState.LOADED) {
                        return;
                    }

                    PluginEvents.fire(trigger);

                } catch (Exception e) {
                    Log.err("Failed to invoke trigger @", trigger);
                    Log.err(e);
                }
            });
        }
    }
}
