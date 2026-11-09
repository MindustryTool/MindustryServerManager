package plugin.core;

import arc.files.Fi;
import arc.util.Log;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import plugin.annotations.Configuration;
import plugin.utils.JsonUtils;

import java.io.IOException;
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

        load(config, instance, file);
        startWatcher(config, instance, file);
    }

    private void load(Configuration config, Object instance, Fi file) {
        try {
            if (!file.exists()) {
                Log.warn("Configuration file not found: @", file.absolutePath());

                if (instance != null) {
                    save(config, instance, file);
                }

                return;
            }

            byte[] jsonBytes = Files.readAllBytes(file.file().toPath());
            JsonNode root = JsonUtils.getObjectMapper().readTree(jsonBytes);

            int fileVersion = root != null && root.has("version") && root.get("version").isInt()
                    ? root.get("version").asInt()
                    : 0;

            if (fileVersion < config.version()) {
                Log.info("Configuration @ is outdated (file version: @, code version: @). Overwriting with code defaults.",
                        file.name(), fileVersion, config.version());

                if (instance != null) {
                    save(config, instance, file);
                }

                return;
            }

            if (fileVersion > config.version()) {
                Log.warn("Configuration file @ has newer version @ than code version @",
                        file.name(), fileVersion, config.version());
            }

            Object newData = JsonUtils.readJsonAsClass(root, instance.getClass());

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

    private void save(Configuration config, Object instance, Fi file) throws IOException {
        JsonNode tree = JsonUtils.getObjectMapper().valueToTree(instance);
        ObjectNode root = JsonUtils.createObjectNode();
        root.put("version", config.version());
        if (tree instanceof ObjectNode objectNode) {
            objectNode.remove("version");
            root.setAll(objectNode);
        }
        String prettyJson = JsonUtils.getObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
        file.writeString(prettyJson);
    }

    private void startWatcher(Configuration config, Object instance, Fi file) {
        fileWatcher.watch(file.file().toPath(),
                path -> load(config, instance, file),
                100,
                StandardWatchEventKinds.ENTRY_MODIFY);
    }
}
