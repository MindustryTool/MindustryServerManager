package plugin.core;

import plugin.annotations.ConditionOn;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Lazy;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

public final class Registry {

    private static final Map<Class<?>, Object> instances = new LinkedHashMap<>();
    private static final Set<Class<?>> creating = new HashSet<>();
    private static final Set<Object> initialized = new HashSet<>();

    private static final Map<Class<? extends Annotation>, List<ClassAnnotationHandler<?>>> classHandlers = new LinkedHashMap<>();
    private static final Map<Class<? extends Annotation>, List<FieldAnnotationHandler<?>>> fieldHandlers = new LinkedHashMap<>();
    private static final Map<Class<? extends Annotation>, List<MethodAnnotationHandler<?>>> methodHandlers = new LinkedHashMap<>();

    private static RegistryLogger logger = new RegistryLogger() {
        @Override
        public void debug(String message, Object... args) {
        }

        @Override
        public void info(String message, Object... args) {
        }

        @Override
        public void error(String message, Throwable throwable, Object... args) {
            System.err.println(format(message, args));
            if (throwable != null) {
                throwable.printStackTrace();
            }
        }
    };

    private Registry() {
    }

    public static synchronized void setLogger(RegistryLogger customLogger) {
        if (customLogger != null) {
            logger = customLogger;
        }
    }

    public static synchronized <T extends Annotation> void registerClassHandler(Class<T> annotationType, ClassAnnotationHandler<T> handler) {
        classHandlers.computeIfAbsent(annotationType, k -> new ArrayList<>()).add(handler);
    }

    public static synchronized <T extends Annotation> void registerFieldHandler(Class<T> annotationType, FieldAnnotationHandler<T> handler) {
        fieldHandlers.computeIfAbsent(annotationType, k -> new ArrayList<>()).add(handler);
    }

    public static synchronized <T extends Annotation> void registerMethodHandler(Class<T> annotationType, MethodAnnotationHandler<T> handler) {
        methodHandlers.computeIfAbsent(annotationType, k -> new ArrayList<>()).add(handler);
    }

    public static synchronized void clearHandlers() {
        classHandlers.clear();
        fieldHandlers.clear();
        methodHandlers.clear();
    }

    public static void init(String packageName) {
        try {
            Class<?> registryClass = Class.forName("plugin.core.ComponentRegistry");
            Field field = registryClass.getField("COMPONENTS");
            Class<?>[] components = (Class<?>[]) field.get(null);
            init(Arrays.asList(components));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("ComponentRegistry not found on classpath for package " + packageName, e);
        } catch (Exception e) {
            throw new RuntimeException("Registry init failed", e);
        }
    }

    public static void init(Class<?>... components) {
        init(Arrays.asList(components));
    }

    public static void init(Collection<Class<?>> components) {
        try {
            List<Class<?>> filtered = measure("filtering", () -> components.stream()
                    .filter(clazz -> {
                        if (clazz.isAnnotation() || clazz.isInterface()) {
                            return false;
                        }

                        if (isLazy(clazz)) {
                            return false;
                        }

                        if (!ConditionUtils.passes(clazz)) {
                            ConditionOn cond = clazz.getAnnotation(ConditionOn.class);
                            String condName = cond != null ? cond.value().getName() : "unknown";
                            logger.debug("[gray]Skipping component @ due to condition @", clazz.getName(), condName);
                            return false;
                        }

                        return true;
                    })
                    .toList());

            measure("component scan", () -> {
                for (Class<?> clazz : filtered) {
                    getOrCreate(clazz);
                }
            });

        } catch (Exception e) {
            throw new RuntimeException("Registry init failed", e);
        }
    }

    public static synchronized void destroy() {
        List<Object> ordered = new ArrayList<>(instances.values());
        Collections.reverse(ordered);

        for (Object obj : ordered) {
            for (Method method : obj.getClass().getDeclaredMethods()) {
                withAnnotation(method, Destroy.class, d -> {
                    try {
                        method.setAccessible(true);
                        method.invoke(obj);
                    } catch (Exception e) {
                        logger.error("Failed to invoke @Destroy on " + obj.getClass().getName(), e);
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
        if (!ConditionUtils.passes(type)) {
            throw new RuntimeException("Condition mismatch! Component: " + type.getName());
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

            logger.debug("[gray]Registered component: @", type.getName());

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

    @SuppressWarnings("unchecked")
    public static <T> T inject(Class<T> type) {
        try {
            Constructor<?> constructor = selectConstructor(type);

            Object[] args = Arrays.stream(constructor.getParameterTypes())
                    .map(Registry::get)
                    .toArray();

            Object instance = constructor.newInstance(args);

            return (T) instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create prototype " + type.getName(), e);
        }
    }

    private static void initialize(Object instance) {
        if (initialized.contains(instance)) {
            return;
        }

        measure("initialize " + instance.getClass().getName(), () -> {
            Class<?> clazz = instance.getClass();

            // 1. Class annotation handlers
            for (var entry : classHandlers.entrySet()) {
                Class<? extends Annotation> annotationType = entry.getKey();
                withAnnotation(clazz, annotationType, a -> {
                    for (ClassAnnotationHandler<?> handler : entry.getValue()) {
                        @SuppressWarnings("unchecked")
                        ClassAnnotationHandler<Annotation> typed = (ClassAnnotationHandler<Annotation>) handler;
                        typed.handle(a, instance);
                    }
                });
            }

            // 2. Field annotation handlers
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                for (var entry : fieldHandlers.entrySet()) {
                    Class<? extends Annotation> annotationType = entry.getKey();
                    withAnnotation(field, annotationType, a -> {
                        for (FieldAnnotationHandler<?> handler : entry.getValue()) {
                            @SuppressWarnings("unchecked")
                            FieldAnnotationHandler<Annotation> typed = (FieldAnnotationHandler<Annotation>) handler;
                            typed.handle(a, field, instance);
                        }
                    });
                }
            }

            // 3. Method annotation handlers
            for (Method method : clazz.getDeclaredMethods()) {
                method.setAccessible(true);

                // Built-in @Init
                withAnnotation(method, Init.class, a -> {
                    try {
                        method.invoke(instance);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to invoke @Init on " + clazz.getName(), e);
                    }
                });

                // Registered method handlers
                for (var entry : methodHandlers.entrySet()) {
                    Class<? extends Annotation> annotationType = entry.getKey();
                    withAnnotation(method, annotationType, a -> {
                        if (ConditionUtils.passes(method)) {
                            for (MethodAnnotationHandler<?> handler : entry.getValue()) {
                                @SuppressWarnings("unchecked")
                                MethodAnnotationHandler<Annotation> typed = (MethodAnnotationHandler<Annotation>) handler;
                                typed.handle(a, method, instance);
                            }
                        }
                    });
                }
            }

            initialized.add(instance);
        });
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

    private static <T extends Annotation> boolean withAnnotation(
            Field field,
            Class<T> annotation,
            Consumer<T> consumer
    ) {
        if (field.isAnnotationPresent(annotation)) {
            consumer.accept(field.getAnnotation(annotation));
            return true;
        }
        return false;
    }

    private static <T extends Annotation> boolean withAnnotation(
            Method method,
            Class<T> annotation,
            Consumer<T> consumer
    ) {
        if (method.isAnnotationPresent(annotation)) {
            consumer.accept(method.getAnnotation(annotation));
            return true;
        }
        return false;
    }

    private static <T extends Annotation> boolean withAnnotation(
            Class<?> clazz,
            Class<T> annotation,
            Consumer<T> consumer
    ) {
        if (clazz.isAnnotationPresent(annotation)) {
            consumer.accept(clazz.getAnnotation(annotation));
            return true;
        }
        return false;
    }

    private static void measure(String name, Runnable action) {
        long start = System.nanoTime();
        action.run();
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        if (durationMs > 20) {
            logger.info("[#50C878][timing] @ took @ms", name, durationMs);
        }
    }

    private static <T> T measure(String name, java.util.function.Supplier<T> action) {
        long start = System.nanoTime();
        T result = action.get();
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        if (durationMs > 20) {
            logger.info("[#50C878][timing] @ took @ms", name, durationMs);
        }
        return result;
    }

    private static String format(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0) {
            return pattern;
        }
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int lastPos = 0;
        int pos;
        while ((pos = pattern.indexOf('@', lastPos)) != -1 && argIndex < args.length) {
            sb.append(pattern, lastPos, pos);
            sb.append(args[argIndex++]);
            lastPos = pos + 1;
        }
        sb.append(pattern.substring(lastPos));
        return sb.toString();
    }
}
