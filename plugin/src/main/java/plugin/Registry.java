package plugin;

import arc.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class Registry {

    private static final Map<Class<?>, Object> instances = new HashMap<>();

    private Registry() {
    }

    public static void init(Class<?> pluginMainClass) {
        String basePackage = pluginMainClass.getPackage().getName();
        Log.info("Registry scanning package: @", basePackage);

        try {
            for (Class<?> clazz : scanClasses(basePackage)) {
                if (clazz.isAnnotationPresent(Component.class)) {
                    getOrCreate(clazz);
                }
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
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> type) {
        Object obj = instances.get(type);
        if (obj == null) {
            throw new RuntimeException("No component registered for " + type.getName());
        }
        return (T) obj;
    }

    private static Object getOrCreate(Class<?> type) {
        if (instances.containsKey(type)) {
            return instances.get(type);
        }

        try {
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

    private static List<Class<?>> scanClasses(String packageName) throws Exception {
        String path = packageName.replace('.', '/');
        Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(path);

        List<Class<?>> classes = new ArrayList<>();

        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            String filePath = URLDecoder.decode(resource.getFile(), "UTF-8");

            if (filePath.startsWith("file:") && filePath.contains("!")) {

                String jarPath = filePath.substring(5, filePath.indexOf("!"));
                scanJar(jarPath, path, classes);
            } else {

                scanDirectory(new File(filePath), packageName, classes);
            }
        }

        return classes;
    }

    private static void scanDirectory(File dir, String pkg, List<Class<?>> classes) {
        if (!dir.exists())
            return;

        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (file.isDirectory()) {
                scanDirectory(file, pkg + "." + file.getName(), classes);
            } else if (file.getName().endsWith(".class")) {
                try {
                    String className = pkg + "." +
                            file.getName().replace(".class", "");
                    classes.add(Class.forName(className));
                } catch (ClassNotFoundException ignored) {
                }
            }
        }
    }

    private static void scanJar(String jarPath, String path, List<Class<?>> classes) {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();

                if (name.startsWith(path) && name.endsWith(".class")) {
                    String className = name
                            .replace('/', '.')
                            .replace(".class", "");
                    classes.add(Class.forName(className));
                }
            }
        } catch (Exception ignored) {
        }
    }
}
