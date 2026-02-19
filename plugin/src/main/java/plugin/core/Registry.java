package plugin.core;

import arc.Core;
import arc.util.Log;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import plugin.annotations.*;
import plugin.commands.ClientCommandHandler;
import plugin.commands.ServerCommandHandler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

public final class Registry {

    private static final Map<Class<?>, Object> instances = new HashMap<>();
    private static final Set<Class<?>> creating = new HashSet<>();
    private static final Set<Object> initialized = new HashSet<>();

    private static final ConfigManager configManager = get(ConfigManager.class);
    private static final Scheduler scheduler = get(Scheduler.class);
    private static final PersistenceManager persistenceManager = get(PersistenceManager.class);
    private static final EventRegistrar eventRegistrar = get(EventRegistrar.class);
    private static final FileWatcherManager fileWatcherService = get(FileWatcherManager.class);
    private static final ClientCommandHandler clientCommandHandler = get(ClientCommandHandler.class);
    private static final ServerCommandHandler serverCommandHandler = get(ServerCommandHandler.class);

    public static final String GAMEMODE_KEY = "plugin-gamemode";

    private static String currentGamemode;

    public static void init(String packageName) {
        try {
            Reflections reflections = new Reflections(packageName, Scanners.TypesAnnotated);
            Set<Class<?>> components = reflections.getTypesAnnotatedWith(Component.class);

            currentGamemode = Core.settings.getString(GAMEMODE_KEY, null);

            if (currentGamemode != null) {
                Log.info("[sky]Current gamemode: " + currentGamemode);
            }

            for (Class<?> clazz : components.stream().sorted(Comparator.comparingInt(c -> {
                var cons = c.getDeclaredConstructors();

                if (cons.length == 0) {
                    return 1;
                }

                return cons[0].getParameterCount();
            })).toList()) {
                if (clazz.isAnnotation() || clazz.isInterface()) {
                    continue;
                }

                if (isLazy(clazz)) {
                    continue;
                }

                if (clazz.isAnnotationPresent(ConditionOn.class)) {
                    ConditionOn annotation = clazz.getAnnotation(ConditionOn.class);
                    Class<? extends Condition> conditionClass = annotation.value();
                    try {
                        Condition condition = conditionClass.getDeclaredConstructor().newInstance();

                        if (!condition.check()) {
                            Log.info("[gray]Skipping component @ due to condition @", clazz.getName(),
                                    conditionClass.getName());
                            continue;
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to check condition for " + clazz.getName(), e);
                    }
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
        for (Object obj : instances.values()) {
            for (Method method : obj.getClass().getDeclaredMethods()) {
                withAnnotation(method, Destroy.class, d -> {
                    try {
                        method.setAccessible(true);
                        method.invoke(obj);
                    } catch (Exception e) {
                        Log.err("Failed to invoke @Destroy on " + obj.getClass().getName(), e);
                    }
                });
            }
        }

        instances.clear();
        initialized.clear();
    }

    @SuppressWarnings("unchecked")
    public static synchronized <T> T get(Class<T> type) {
        Object obj = instances.get(type);

        if (obj == null) {
            return (T) getOrCreate(type);
        }

        return (T) obj;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getOrNull(Class<T> type) {
        Object obj = instances.get(type);

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

        var instance = create(type);

        instances.put(type, instance);

        return instance;
    }

    private static Object create(Class<?> type) {
        if (type.isAnnotationPresent(Gamemode.class)) {
            Gamemode gamemode = type.getAnnotation(Gamemode.class);
            if (!Objects.equals(currentGamemode, gamemode.value())) {
                throw new RuntimeException("Gamemode mismatch!" +
                        "Current: " + currentGamemode +
                        "\nComponent: " + type.getName() +
                        "\nExpects " + gamemode.value());
            }
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

            Log.info("[gray]Registered component: @", type.getName());

            initialize(instance);

            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create component " + type.getName(), e);
        } finally {
            creating.remove(type);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T createNew(Class<T> type) {
        try {
            Constructor<?> constructor = selectConstructor(type);

            Object[] args = Arrays.stream(constructor.getParameterTypes())
                    .map(Registry::get)
                    .toArray();

            Object instance = constructor.newInstance(args);

            initialize(instance);

            return (T) instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create prototype " + type.getName(), e);
        }
    }

    private static void initialize(Object instance) {
        if (initialized.contains(instance)) {
            return;
        }

        Class<?> clazz = instance.getClass();

        withAnnotation(clazz, Configuration.class, a -> configManager.process(a, instance));

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);

            withAnnotation(field, Persistence.class, f -> persistenceManager.load(field, f, instance));
        }

        for (Method method : clazz.getDeclaredMethods()) {
            method.setAccessible(true);

            withAnnotation(method, Init.class, a -> {
                try {
                    method.invoke(instance);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to invoke @Init on " + clazz.getName(), e);
                }
            });

            withAnnotation(method, Schedule.class, a -> scheduler.process(a, instance, method));
            withAnnotation(method, Listener.class, a -> eventRegistrar.register(a, instance, method));
            withAnnotation(method, Trigger.class, a -> eventRegistrar.register(a, instance, method));
            withAnnotation(method, FileWatcher.class, a -> fileWatcherService.process(a, instance, method));
            withAnnotation(method, ClientCommand.class, a -> clientCommandHandler.addCommand(a, method, instance));
            withAnnotation(method, ServerCommand.class, a -> serverCommandHandler.addCommand(a, method, instance));
        }

        initialized.add(instance);
    }

    private static boolean isLazy(Class<?> type) {
        if (type.isAnnotationPresent(Lazy.class)) {
            return true;
        }

        for (Annotation a : type.getAnnotations()) {
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

        throw new RuntimeException("Multiple constructors found in " + type.getName() + ", no default constructor");
    }

    private static <T extends Annotation> void withAnnotation(
            Field method,
            Class<T> annotation,
            Consumer<T> consumer//
    ) {
        if (method.isAnnotationPresent(annotation)) {
            consumer.accept(method.getAnnotation(annotation));
        }
    }

    private static <T extends Annotation> void withAnnotation(
            Method method,
            Class<T> annotation,
            Consumer<T> consumer//
    ) {
        if (method.isAnnotationPresent(annotation)) {
            consumer.accept(method.getAnnotation(annotation));
        }
    }

    private static <T extends Annotation> void withAnnotation(
            Class<?> clazz,
            Class<T> annotation,
            Consumer<T> consumer//
    ) {
        if (clazz.isAnnotationPresent(annotation)) {
            consumer.accept(clazz.getAnnotation(annotation));
        }
    }
}
