package plugin.core;

import arc.files.Fi;
import arc.util.Log;
import mindustry.Vars;
import plugin.annotations.Configuration;
import plugin.service.FileWatcherService;
import plugin.utils.JsonUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class ConfigManager {
    private final FileWatcherService fileWatcher;

    public ConfigManager(FileWatcherService fileWatcher) {
        this.fileWatcher = fileWatcher;
    }

    public void process(Configuration config, Object instance) {
        String path = config.value();

        Fi file = Vars.dataDirectory.child(path);

        if (!file.exists()) {
            file.parent().mkdirs();
        }

        load(instance, file);
        startWatcher(instance, file);
    }

    private void load(Object instance, Fi file) {
        try {
            if (!file.exists()) {
                Log.warn("Configuration file not found: @", file.absolutePath());

                if (instance != null) {
                    String json = JsonUtils.toJsonString(instance);
                    file.writeString(json);
                }

                return;
            }

            String json = new String(Files.readAllBytes(file.file().toPath()), StandardCharsets.UTF_8);
            Object newData = JsonUtils.readJsonAsClass(json, instance.getClass());

            for (Field field : instance.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    Log.warn("Field @ is static or final, skip loading", field.getName());
                    continue;
                }

                field.setAccessible(true);
                Object value = field.get(newData);
                field.set(instance, value);
            }
            Log.info("Loaded configuration for @ from @", instance.getClass().getSimpleName(), file.name());
        } catch (Exception e) {
            Log.err("Failed to load configuration for @", instance.getClass().getName());
            Log.err(e);
        }
    }

    private void startWatcher(Object instance, Fi file) {
        Log.info("Create watcher for @ at @", instance.getClass(), file.absolutePath());

        fileWatcher.watch(file.file().toPath(),
                path -> load(instance, file),
                100,
                StandardWatchEventKinds.ENTRY_MODIFY);
    }
}
