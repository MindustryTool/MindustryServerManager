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
import arc.util.Http.HttpStatusException;
import mindustry.game.EventType;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class PluginLoader extends Plugin {

    public final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));

    private final PluginManager pluginManager;
    private final ConcurrentHashMap<PluginData, MindustryToolPlugin> plugins = new ConcurrentHashMap<>();
    private final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    private final List<PluginData> PLUGINS = Arrays.asList(
            new PluginData("mindustry-tool", "ServerController.jar",
                    "MindustryTool",
                    "MindustryServerManager",
                    "plugin"));

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

        List<String> loaded = pluginManager.getPlugins()//
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
            } catch (HttpStatusException e) {
                Log.err("Error while checking and updating plugin " + plugin.getName() + ": " + e.getMessage());
            } catch (Throwable e) {
                Log.err("Error while checking and updating plugin " + plugin.getName(), e);
            }
        }
    }

    private ObjectNode readMeta() {
        try {
            ObjectNode meta = objectMapper.createObjectNode();

            if (Files.exists(PLUGIN_METADATA_PATH)) {
                String data = new Fi(PLUGIN_METADATA_PATH.toFile()).readString();
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
                .put("updated_at", updatedAt);

        Fi metaFile = new Fi(PLUGIN_METADATA_PATH.toFile());

        metaFile.writeString(meta.toPrettyString());
    }

    private synchronized void checkAndUpdate(PluginData pluginData) throws Exception {
        String updatedAt = pluginData.getPluginVersion().getUpdatedAt();
        String currentUpdatedAt = readCurrentUpdatedAt(pluginData);

        Path path = getPluginPath(pluginData);

        boolean hasFile = Files.exists(path);
        boolean isPluginLoaded = plugins.containsKey(pluginData);

        if (Objects.equals(updatedAt, currentUpdatedAt)) {
            if (isPluginLoaded)
                return;

            if (hasFile) {
                loadPlugin(pluginData);
                Log.info("Load cached plugin: " + pluginData.getName());
                return;
            }
        } else {
            Log.info("Detect new version of plugin: " + pluginData.getName());
            Log.info("Old: " + currentUpdatedAt);
            Log.info("New: " + updatedAt);
        }

        Log.info("Downloading plugin: " + pluginData.getName());

        Fi pluginFile = new Fi(path.toFile());

        byte[] data = pluginData.download();

        Log.info("Downloaded: " + pluginData.getName() + ":" + updatedAt);

        unloadPlugin(pluginData);

        Log.info("Deleting old plugin file: " + pluginFile);
        pluginFile.delete();

        Log.info("Writing new plugin file: " + pluginFile);
        pluginFile.writeBytes(data);

        Log.info("Updating metadata: " + pluginData.getName());
        writeUpdatedAt(pluginData, updatedAt);

        loadPlugin(pluginData);
    }

    private Path getPluginPath(PluginData plugin) {
        return Paths.get(PLUGIN_DIR, plugin.getName().replaceAll("[^a-zA-Z0-9_.-]", "_"));
    }

    private void unloadPlugin(PluginData pluginData) {
        try {
            MindustryToolPlugin plugin = plugins.get(pluginData);

            if (plugin != null) {
                plugin.unload();
            }

            plugins.remove(pluginData);
            pluginManager.stopPlugin(pluginData.getId());
            pluginManager.unloadPlugin(pluginData.getId());

            Log.info("Unloaded plugin: " + pluginData.getName());
        } catch (Exception e) {
            Log.err("Error while unloading plugin " + pluginData.getName(), e);
        }
    }

    private synchronized void loadPlugin(PluginData pluginData) {
        if (plugins.containsKey(pluginData)) {
            throw new RuntimeException("Plugin already loaded: " + pluginData.getName());
        }

        Log.info("Loading plugin: " + pluginData.getName());

        Path path = getPluginPath(pluginData);

        try {
            pluginManager.loadPlugin(path);

            PluginWrapper wrapper = pluginManager.getPlugin(pluginData.getId());

            if (wrapper == null) {
                throw new RuntimeException("Plugin not found: " + pluginData.getId());
            }

            Log.info("State: " + wrapper.getPluginState().name());
            Log.info("State: " + pluginManager.startPlugin(pluginData.getId()));

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

                plugins.put(pluginData, mindustryToolPlugin);

                Log.info("Plugin loaded: " + pluginData.getName());
            } else {
                Log.info("Invalid plugin: " + instance.getClass().getName());
            }

        } catch (PluginRuntimeException e) {
            plugins.remove(pluginData);
            getPluginPath(pluginData).toFile().delete();
        } catch (Exception e) {
            plugins.remove(pluginData);
            Log.err(e);

            throw new RuntimeException("Failed to load plugin: " + pluginData.getName(), e);
        }
    }
}
