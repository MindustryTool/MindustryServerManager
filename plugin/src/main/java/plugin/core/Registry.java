package plugin.core;

import arc.Core;
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

    private static final ConfigManager configManager = new ConfigManager();
    private static final PersistenceManager persistenceManager = new PersistenceManager();
    private static final EventRegistrar eventRegistrar = new EventRegistrar();

    private static String currentGamemode;

    public static void init(String packageName) {
        try {
            Reflections reflections = new Reflections(packageName, Scanners.TypesAnnotated);
            Set<Class<?>> components = reflections.getTypesAnnotatedWith(Component.class);

            currentGamemode = Core.settings.getString("plugin-gamemode", null);

            for (Class<?> clazz : components) {
                if (clazz.isAnnotation() || clazz.isInterface()) {
                    continue;
                }

                // Check Lazy
                if (isLazy(clazz)) {
                    continue;
                }

                if (clazz.isAnnotationPresent(Gamemode.class)) {
                    Gamemode gamemode = clazz.getAnnotation(Gamemode.class);
                    if (!Objects.equals(currentGamemode, gamemode.value())) {
                        continue;
                    }
                }

                getOrCreate(clazz);
            }
        } catch (Exception e) {
            throw new RuntimeException("Registry init failed", e);
        }
    }

    public static void destroy() {
        instances.values().forEach(obj -> {
            persistenceManager.save(obj);

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
        configManager.destroy();
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

        // Check Gamemode
        if (type.isAnnotationPresent(Gamemode.class)) {
            Gamemode gamemode = type.getAnnotation(Gamemode.class);
            if (!Objects.equals(currentGamemode, gamemode.value())) {
                throw new RuntimeException("Gamemode mismatch! Current: " + currentGamemode + ", Component: "
                        + type.getName() + " expects " + gamemode.value());
            }
        }

        if (creating.contains(type)) {
            throw new RuntimeException("Circular dependency detected: " + type.getName());
        }

        if (type.isAnnotationPresent(ConditionOn.class)) {
            ConditionOn annotation = type.getAnnotation(ConditionOn.class);
            Class<? extends Condition> conditionClass = annotation.value();
            try {
                Condition condition = conditionClass.getDeclaredConstructor().newInstance();

                if (!condition.check()) {
                    Log.info("Skipping component @ due to condition", type.getName());
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

    private static void initialize(Object instance) {
        if (initialized.contains(instance)) {
            return;
        }

        Class<?> clazz = instance.getClass();

        if (clazz.isAnnotationPresent(Configuration.class)) {
            configManager.process(instance);
        }

        persistenceManager.load(instance);

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Init.class)) {
                try {
                    method.setAccessible(true);
                    method.invoke(instance);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to invoke @Init on " + clazz.getName(), e);
                }
            }
        }

        eventRegistrar.register(instance);

        initialized.add(instance);
    }

    private static boolean isLazy(Class<?> type) {
        if (type.isAnnotationPresent(Lazy.class)) {
            return true;
        }
        for (java.lang.annotation.Annotation a : type.getAnnotations()) {
            if (a.annotationType().isAnnotationPresent(Lazy.class)) {
                return true;
            }
        }
        return false;
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
