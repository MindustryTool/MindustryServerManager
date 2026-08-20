package plugin.core;

import arc.files.Fi;
import arc.util.Log;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import plugin.annotations.Configuration;
import plugin.utils.JsonUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.*;

@RequiredArgsConstructor
public class ConfigManager {
    private final FileWatcherManager fileWatcher;

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

            byte[] jsonBytes = Files.readAllBytes(file.file().toPath());
            Object newData = JsonUtils.readJsonAsArrayClass(jsonBytes, instance.getClass());

            for (Field field : instance.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    Log.warn("Field @ is static or final, skip loading", field.getName());
                    continue;
                }

                field.setAccessible(true);
                Object value = field.get(newData);
                field.set(instance, value);
            }
            Log.debug("[gray]Loaded configuration for @ from @", instance.getClass().getSimpleName(), file.name());
        } catch (Exception e) {
            Log.err("Failed to load configuration for @", instance.getClass().getName());
            Log.err(e);
        }
    }

    private void startWatcher(Object instance, Fi file) {
        fileWatcher.watch(file.file().toPath(),
                path -> load(instance, file),
                100,
                StandardWatchEventKinds.ENTRY_MODIFY);
    }
}
