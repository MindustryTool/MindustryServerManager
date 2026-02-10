package plugin.core;

import arc.func.Cons;
import arc.util.Log;
import plugin.PluginEvents;
import plugin.annotations.Listener;

import java.lang.reflect.Method;

public class EventRegistrar {

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void register(Listener listener, Object instance, Method method) {
        Class<?> clazz = instance.getClass();
        int paramCount = method.getParameterCount();

        if (paramCount == 1) {
            Class<?> eventType = method.getParameterTypes()[0];
            PluginEvents.on(eventType, (Cons) event -> {
                try {
                    method.invoke(instance, event);
                } catch (Exception e) {
                    Log.err("Failed to invoke listener @ in @", method.getName(), clazz.getName());
                    Log.err(e);
                }
            });
        } else if (paramCount == 0) {
            Class<?> eventType = listener.value();

            if (eventType == Object.class) {
                Log.err("@Listener method @ in @ with 0 parameters must specify event type in annotation",
                        method.getName(),
                        clazz.getName());
                return;
            }

            PluginEvents.run(eventType, () -> {
                try {
                    method.invoke(instance);
                } catch (Exception e) {
                    Log.err("Failed to invoke listener @ in @", method.getName(), clazz.getName(), e);
                }
            });
        } else {
            Log.err("@Listener method @ in @ must have exactly 0 or 1 parameter", method.getName(), clazz.getName());
        }
    }
}
