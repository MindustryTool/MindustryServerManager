package plugin;

import arc.func.Cons;
import arc.util.Log;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import plugin.annotations.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

public final class Registry {

    private static final Map<Class<?>, Object> instances = new HashMap<>();
    private static final Set<Class<?>> creating = new HashSet<>();
    private static final Set<Object> initialized = new HashSet<>();

    private Registry() {
    }

    public static void init(String packageName) {
        try {
            Reflections reflections = new Reflections(packageName, Scanners.TypesAnnotated);
            Set<Class<?>> components = reflections.getTypesAnnotatedWith(Component.class);

            for (Class<?> clazz : components) {
                // Check Lazy
                if (clazz.isAnnotationPresent(Lazy.class)) {
                    continue;
                }

                getOrCreate(clazz);
            }
        } catch (Exception e) {
            throw new RuntimeException("Registry init failed", e);
        }
    }

    public static void destroy() {
        instances.values().forEach(obj -> {
            // Process @Destroy
            for (Method method : obj.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Destroy.class)) {
                    try {
                        method.setAccessible(true);
                        method.invoke(obj);
                    } catch (Exception e) {
                        Log.err("Failed to invoke @Destroy on " + obj.getClass().getName(), e);
                    }
                }
            }
        });
        instances.clear();
        initialized.clear();
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> type) {
        Object obj = instances.get(type);
        if (obj == null) {
            // Allow on-demand creation if it's a component or we want to allow lazy loading
            return (T) getOrCreate(type);
        }
        return (T) obj;
    }

    public static <T> List<T> getAll(Class<T> type) {
        List<T> list = new ArrayList<>();
        for (Object obj : instances.values()) {
            if (type.isInstance(obj)) {
                list.add(type.cast(obj));
            }
        }
        return list;
    }

    private static Object getOrCreate(Class<?> type) {
        if (instances.containsKey(type)) {
            Object instance = instances.get(type);
            initialize(instance);
            return instance;
        }

        if (creating.contains(type)) {
            throw new RuntimeException("Circular dependency detected: " + type.getName());
        }

        // Check ConditionOn
        if (type.isAnnotationPresent(ConditionOn.class)) {
            ConditionOn annotation = type.getAnnotation(ConditionOn.class);
            Class<? extends Condition> conditionClass = annotation.value();
            try {
                // Instantiate the condition class (must have default constructor)
                Condition condition = conditionClass.getDeclaredConstructor().newInstance();
                if (!condition.check()) {
                    Log.info("Skipping component @ due to condition", type.getName());
                    // Return null if condition fails.
                    // Note: If this component is required by another, this will cause a null
                    // injection
                    // or NPE later. This is expected behavior for optional dependencies,
                    // but strict dependencies will fail.
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to check condition for " + type.getName(), e);
            }
        }

        try {
            creating.add(type);
            Constructor<?> constructor = selectConstructor(type);
            Object[] args = Arrays.stream(constructor.getParameterTypes())
                    .map(Registry::getOrCreate)
                    .toArray();

            Object instance = constructor.newInstance(args);
            instances.put(type, instance);

            Log.info("Registered component: @", type.getName());

            // Initialize immediately
            initialize(instance);

            return instance;

        } catch (Exception e) {
            throw new RuntimeException("Failed to create component " + type.getName(), e);
        } finally {
            creating.remove(type);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void initialize(Object instance) {
        if (initialized.contains(instance)) {
            return;
        }

        Class<?> clazz = instance.getClass();

        // Scan for @Init
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Init.class)) {
                try {
                    method.setAccessible(true);
                    method.invoke(instance);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to invoke @Init on " + clazz.getName(), e);
                }
            }

            // Scan for @Listener
            if (method.isAnnotationPresent(Listener.class)) {
                Listener annotation = method.getAnnotation(Listener.class);
                int paramCount = method.getParameterCount();

                method.setAccessible(true);

                if (paramCount == 1) {
                    Class<?> eventType = method.getParameterTypes()[0];
                    // Register with PluginEvents
                    PluginEvents.on(eventType, (Cons) event -> {
                        try {
                            method.invoke(instance, event);
                        } catch (Exception e) {
                            Log.err("Failed to invoke listener @ in @", method.getName(), clazz.getName(), e);
                        }
                    });
                } else if (paramCount == 0) {
                    Class<?> eventType = annotation.value();
                    if (eventType == Object.class) {
                        Log.err("@Listener method @ in @ with 0 parameters must specify event type in annotation",
                                method.getName(),
                                clazz.getName());
                        continue;
                    }

                    // Register with PluginEvents
                    PluginEvents.run(eventType, () -> {
                        try {
                            method.invoke(instance);
                        } catch (Exception e) {
                            Log.err("Failed to invoke listener @ in @", method.getName(), clazz.getName(), e);
                        }
                    });
                } else {
                    Log.err("@Listener method @ in @ must have exactly 0 or 1 parameter", method.getName(),
                            clazz.getName());
                }
            }
        }

        initialized.add(instance);
    }

    private static Constructor<?> selectConstructor(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();

        if (constructors.length == 1) {
            constructors[0].setAccessible(true);
            return constructors[0];
        }

        for (Constructor<?> c : constructors) {
            if (c.getParameterCount() == 0) {
                c.setAccessible(true);
                return c;
            }
        }

        throw new RuntimeException(
                "Multiple constructors found in " + type.getName() + ", no default constructor");
    }
}
