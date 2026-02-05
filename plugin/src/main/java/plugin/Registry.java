package plugin;

import arc.util.Log;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import java.lang.reflect.Constructor;
import java.util.*;

public final class Registry {

    private static final Map<Class<?>, Object> instances = new HashMap<>();
    private static final Set<Class<?>> creating = new HashSet<>();

    private Registry() {
    }

    public static void init(String packageName) {
        try {
            Reflections reflections = new Reflections(packageName, Scanners.TypesAnnotated);
            Set<Class<?>> components = reflections.getTypesAnnotatedWith(Component.class);

            for (Class<?> clazz : components) {
                getOrCreate(clazz);
            }
        } catch (Exception e) {
            throw new RuntimeException("Registry init failed", e);
        }

        instances.values().forEach(obj -> {
            if (obj instanceof IComponent) {
                ((IComponent) obj).init();
            }
        });
    }

    public static void destroy() {
        instances.values().forEach(obj -> {
            if (obj instanceof IComponent) {
                ((IComponent) obj).destroy();
            }
        });
        instances.clear();
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
            return instances.get(type);
        }

        if (creating.contains(type)) {
            throw new RuntimeException("Circular dependency detected: " + type.getName());
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
            return instance;

        } catch (Exception e) {
            throw new RuntimeException("Failed to create component " + type.getName(), e);
        } finally {
            creating.remove(type);
        }
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
