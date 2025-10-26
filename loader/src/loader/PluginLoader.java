package loader;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginWrapper;

import arc.util.CommandHandler;
import mindustry.mod.Plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.util.Log;
import mindustry.game.EventType;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class PluginLoader extends Plugin {

    public final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));

    private final PluginManager pluginManager;
    private final ConcurrentHashMap<String, WeakReference<MindustryToolPlugin>> plugins = new ConcurrentHashMap<>();
    private final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    private final List<PluginData> PLUGINS = Arrays.asList(
            new PluginData("mindustry-tool", "ServerController.jar",
                    "https://api.github.com/repos/MindustryTool/MindustryServerManager/releases/tags/plugin"));

    private final String PLUGIN_DIR = "config/mindustry-tool-plugins";
    private final Path PLUGIN_METADATA_PATH = Paths.get(PLUGIN_DIR, "metadata.json");

    private CommandHandler clientCommandHandler;
    private CommandHandler serverCommandHandler;

    public static final ObjectMapper objectMapper = new ObjectMapper();

    public PluginLoader() {
        try {
            if (!Files.exists(Paths.get(PLUGIN_DIR))) {
                Files.createDirectories(Paths.get(PLUGIN_DIR));
            }
        } catch (IOException e) {
            Log.err("Error while init plugin dir: " + PLUGIN_DIR, e);
        }

        pluginManager = new DefaultPluginManager();
    }

    @Override
    public void init() {
        Core.settings.put("startedAt", System.currentTimeMillis());

        registerEventListener();
        registerTriggerListener();

        checkAndUpdate();

        BACKGROUND_SCHEDULER.scheduleWithFixedDelay(this::checkAndUpdate, 5, 5, TimeUnit.MINUTES);

        var loaded = pluginManager.getPlugins()//
                .stream()//
                .map(plugin -> plugin.getPluginId())
                .collect(Collectors.toList());

        Log.info("Loaded plugins: " + loaded);

        System.out.println("MindustryToolPluginLoader initialized");
    }

    private void registerEventListener() {
        for (Class<?> clazz : EventType.class.getDeclaredClasses()) {
            Events.on(clazz, this::onEvent);
        }
    }

    private void registerTriggerListener() {
        for (EventType.Trigger trigger : EventType.Trigger.values()) {
            Events.run(trigger, () -> onEvent(trigger));
        }
    }

    public void forEachPlugin(Consumer<MindustryToolPlugin> consumer) {
        plugins.values()
                .stream()
                .map(value -> value.get())
                .filter(value -> value != null)
                .forEach(consumer);
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        serverCommandHandler = handler;
        forEachPlugin((plugin) -> plugin.registerServerCommands(handler));
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        clientCommandHandler = handler;
        forEachPlugin((plugin) -> plugin.registerClientCommands(handler));
    }

    public void onEvent(Object event) {
        try {
            forEachPlugin((plugin) -> plugin.onEvent(event));
        } catch (Exception e) {
            Log.err("Error while handing event", e);
        }
    }

    public void checkAndUpdate() {
        for (PluginData plugin : PLUGINS) {
            try {
                checkAndUpdate(plugin);
            } catch (Exception e) {
                Log.err("Error while checking and updating plugin " + plugin.getName(), e);
            }
        }
    }

    private ObjectNode readMeta() {
        try {
            ObjectNode meta = objectMapper.createObjectNode();

            if (Files.exists(PLUGIN_METADATA_PATH)) {
                var data = new Fi(PLUGIN_METADATA_PATH.toFile()).readString();
                meta = (ObjectNode) objectMapper.readTree(data);

                return meta;
            }

            return meta;
        } catch (Exception e) {
            throw new RuntimeException("Error while reading: " + PLUGIN_METADATA_PATH, e);
        }
    }

    private String readCurrentUpdatedAt(PluginData plugin) {
        ObjectNode meta = readMeta();

        if (meta.path(plugin.getName()).has("updated_at")) {
            return meta.path(plugin.getName()).path("updated_at").asText(null);
        }

        return null;
    }

    private void writeUpdatedAt(PluginData plugin, String updatedAt) {
        ObjectNode meta = readMeta();

        meta
                .putObject(plugin.getName())
                .put("updated_at", updatedAt)
                .put("url", plugin.getUrl());

        Fi metaFile = new Fi(PLUGIN_METADATA_PATH.toFile());

        metaFile.writeString(meta.toPrettyString());
    }

    private void checkAndUpdate(PluginData plugin) throws Exception {
        String updatedAt = plugin.getPluginVersion().getUpdatedAt();
        String currentUpdatedAt = readCurrentUpdatedAt(plugin);

        Path path = getPluginPath(plugin);

        if (updatedAt == null && Files.exists(path)) {
            // Use the current version if can not find the newest
            loadPlugin(plugin);
            return;
        }

        if (Objects.equals(updatedAt, currentUpdatedAt) && Files.exists(path)) {
            loadPlugin(plugin);
            return;
        }

        Log.info("Downloading updated plugin: " + plugin.getName());

        Fi pluginFile = new Fi(path.toString());

        byte[] data = plugin.download();

        pluginFile.writeBytes(data);

        writeUpdatedAt(plugin, updatedAt);

        unloadPlugin(plugin);
        loadPlugin(plugin);
    }

    private Path getPluginPath(PluginData plugin) {
        return Paths.get(PLUGIN_DIR, plugin.getName().replaceAll("[^a-zA-Z0-9_.-]", "_"));
    }

    private void unloadPlugin(PluginData plugin) {
        plugins.remove(plugin.getId());

        PluginWrapper loaded = pluginManager.getPlugin(plugin.getId());

        if (loaded != null) {
            pluginManager.unloadPlugin(loaded.getPluginId());

            Log.info("Unloaded plugin: " + plugin.getName());
        }
    }

    private void loadPlugin(PluginData plugin) {
        if (plugins.containsKey(plugin.getId())) {
            return;
        }

        Path path = getPluginPath(plugin);

        try {
            String pluginId = pluginManager.loadPlugin(path);
            PluginWrapper wrapper = pluginManager.getPlugin(pluginId);

            if (wrapper == null) {
                throw new RuntimeException("Plugin not found: " + pluginId);
            }

            pluginManager.startPlugin(pluginId);
            org.pf4j.Plugin instance = wrapper.getPlugin();

            if (instance instanceof MindustryToolPlugin) {
                MindustryToolPlugin mindustryToolPlugin = (MindustryToolPlugin) instance;

                Log.info("Init plugin: " + mindustryToolPlugin.getClass().getName());

                mindustryToolPlugin.init();

                if (clientCommandHandler != null) {
                    mindustryToolPlugin.registerClientCommands(clientCommandHandler);
                }

                if (serverCommandHandler != null) {
                    mindustryToolPlugin.registerServerCommands(serverCommandHandler);
                }

                plugins.put(pluginId, new WeakReference<>(mindustryToolPlugin));

                Log.info("Plugin updated and reloaded: " + plugin.getName());
            } else {
                Log.info("Invalid plugin: " + instance.getClass().getName());
            }

        } catch (PluginRuntimeException e) {
            plugins.remove(plugin.getId());
            getPluginPath(plugin).toFile().delete();
        } catch (Exception e) {
            plugins.remove(plugin.getId());
            Log.err(e);

            throw new RuntimeException("Failed to load plugin: " + plugin.getName(), e);
        }
    }
}
