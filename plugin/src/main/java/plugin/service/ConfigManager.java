package plugin.service;

import arc.files.Fi;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import plugin.annotations.Configuration;
import plugin.utils.JsonUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConfigManager {
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ConfigWatcher");
        t.setDaemon(true);
        return t;
    });

    public void process(Object instance) {
        Class<?> clazz = instance.getClass();
        if (!clazz.isAnnotationPresent(Configuration.class)) {
            throw new IllegalArgumentException(Strings.format("Class @ is not annotated with @", clazz.getName(),
                    Configuration.class.getName()));
        }

        Configuration config = clazz.getAnnotation(Configuration.class);
        String path = config.value();
        Fi file = Vars.dataDirectory.child(path);

        // Initial load
        load(instance, file);

        // Watch
        startWatcher(instance, file);
    }

    private void load(Object instance, Fi file) {
        if (!file.exists()) {
            Log.warn("Configuration file not found: @", file.absolutePath());

            String json = JsonUtils.toJsonString(instance);
            file.writeString(json);

            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.file().toPath()), StandardCharsets.UTF_8);
            Object newData = JsonUtils.readJsonAsClass(json, instance.getClass());

            for (Field field : instance.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }

                field.setAccessible(true);
                Object value = field.get(newData);
                field.set(instance, value);
            }
            Log.info("Loaded configuration for @ from @", instance.getClass().getSimpleName(), file.name());
        } catch (Exception e) {
            Log.err("Failed to load configuration for @", instance.getClass().getName(), e);
        }
    }

    private void startWatcher(Object instance, Fi file) {
        Log.info("Create watcher for @ at @", instance.getClass(), file.absolutePath());

        executor.submit(() -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                Path dir = file.file().getAbsoluteFile().getParentFile().toPath();

                dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);

                while (true) {
                    WatchKey key;
                    try {
                        key = watcher.take();
                    } catch (InterruptedException x) {
                        return;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW)
                            continue;

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path filename = ev.context();

                        if (filename.toString().equals(file.name())) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException ignored) {
                            }

                            load(instance, file);
                        }
                    }

                    boolean valid = key.reset();
                    if (!valid)
                        break;
                }
            } catch (Exception e) {
                Log.err("Watcher failed for @", file.absolutePath(), e);
            }
        });
    }

    public void destroy() {
        executor.shutdownNow();
    }
}
