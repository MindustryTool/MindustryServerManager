package loader;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginWrapper;

import arc.util.CommandHandler;
import arc.util.Http;
import mindustry.mod.Plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.util.Log;
import mindustry.game.EventType;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class MindustryToolPluginLoader extends Plugin {

    public final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));

    private final PluginManager pluginManager;
    private final ConcurrentHashMap<String, WeakReference<MindustryToolPlugin>> plugins = new ConcurrentHashMap<>();
    public final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    public class PluginData {
        final String id;
        final String name;
        final String url;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }

        public PluginData(String id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }
    }

    private final List<PluginData> PLUGINS = Arrays.asList(
            new PluginData("mindustry-tool",
                    "ServerController.jar",
                    "https://api.github.com/repos/MindustryTool/ServerController/releases/latest"));

    private final String PLUGIN_DIR = "config/plugins";
    private final Path METADATA_PATH = Paths.get("config/plugin-meta.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommandHandler clientCommandHandler;
    private CommandHandler serverCommandHandler;

    public MindustryToolPluginLoader() {
        try {
            if (!Files.exists(Paths.get(PLUGIN_DIR))) {
                Files.createDirectories(Paths.get(PLUGIN_DIR));
            }

            new Fi(PLUGIN_DIR).emptyDirectory();

        } catch (IOException e) {
            Log.err(e);
        }

        pluginManager = new DefaultPluginManager();
    }

    @Override
    public void init() {
        Core.settings.put("startedAt", System.currentTimeMillis());

        try {
            checkAndUpdate();
        } catch (Exception error) {
            Log.err(error);
        }

        for (Class<?> clazz : EventType.class.getDeclaredClasses()) {
            try {
                Events.on(clazz, this::onEvent);
            } catch (Exception e) {
                Log.err("Failed to register event: " + clazz.getName(), e);
            }
        }

        for (EventType.Trigger trigger : EventType.Trigger.values()) {
            try {
                Events.run(trigger, () -> onEvent(trigger));
            } catch (Exception e) {
                Log.err("Failed to register trigger: " + trigger.name(), e);
            }
        }

        BACKGROUND_SCHEDULER.scheduleWithFixedDelay(this::checkAndUpdate, 5, 5, TimeUnit.MINUTES);

        Log.info("Loaded plugins: "
                + pluginManager.getPlugins().stream().map(plugin -> plugin.getPluginId()).collect(Collectors.toList()));

        System.out.println("MindustryToolPluginLoader initialized");
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        serverCommandHandler = handler;
        plugins.values()
                .stream()
                .map(value -> value.get())
                .filter(value -> value != null)
                .forEach((plugin) -> plugin.registerServerCommands(handler));

    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        clientCommandHandler = handler;
        plugins.values()
                .stream()
                .map(value -> value.get())
                .filter(value -> value != null)
                .forEach((plugin) -> plugin.registerClientCommands(handler));
    }

    public void onEvent(Object event) {
        try {
            plugins.values()
                    .stream()
                    .map(value -> value.get())
                    .filter(value -> value != null)
                    .forEach((plugin) -> plugin.onEvent(event));
        } catch (Exception e) {
            Log.err(e);
        }
    }

    public void checkAndUpdate() {
        for (PluginData plugin : PLUGINS) {
            try {
                checkAndUpdate(plugin);
            } catch (Exception e) {
                Log.err(e);
            }
        }
    }

    private String getPluginVersion(String url) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();

        Http.get(URI.create("https://api.mindustry-tool.com/api/v3/plugins/version?path=" + url).toString())
                .error(error -> {
                    result.completeExceptionally(error);
                    Log.err(error);
                })
                .timeout(5000)
                .submit(res -> {
                    String version = res.getResultAsString();
                    result.complete(version);
                });

        return result.get(5, TimeUnit.SECONDS);
    }

    private void downloadFile(String pluginUrl, String savePath) {
        try {
            URL url = URI.create("https://api.mindustry-tool.com/api/v3/plugins/download?path=" + pluginUrl).toURL();
            HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
            httpConn.setReadTimeout(60_000);
            httpConn.setConnectTimeout(60_000);
            int responseCode = httpConn.getResponseCode();

            // Check HTTP response code
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedInputStream in = new BufferedInputStream(httpConn.getInputStream());
                        FileOutputStream out = new FileOutputStream(savePath)) {

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            } else {
                throw new IOException("Server returned non-OK response code: " + responseCode);
            }

            httpConn.disconnect();
        } catch (Exception e) {
            File file = new File(savePath);
            if (file.exists()) {
                file.delete();
            }

            throw new RuntimeException(e);
        }
    }

    public void checkAndUpdate(PluginData plugin) throws Exception {
        String updatedAt = "";
        try {
            updatedAt = getPluginVersion(plugin.getUrl());
        } catch (Exception error) {
            Log.err("Failed to check newest version: " + plugin.name, error);
        }

        String lastUpdated = null;
        ObjectNode meta = objectMapper.createObjectNode();

        if (Files.exists(METADATA_PATH)) {
            meta = (ObjectNode) objectMapper.readTree(new Fi(METADATA_PATH.toFile()).readString());

            if (meta.has(plugin.name) && meta.path(plugin.name).has("updated_at")) {
                lastUpdated = meta.path(plugin.name).path("updated_at").asText(null);
            }
        }

        Path path = Paths.get(PLUGIN_DIR, plugin.name);

        if (updatedAt == null) {
            // Use the current version if can not find the newest
            loadPlugin(plugin);
            return;
        }

        if (Objects.equals(updatedAt, lastUpdated) && Files.exists(path)) {
            loadPlugin(plugin);
            return;
        }

        // Download new plugin
        Log.info("Downloading updated plugin: " + plugin.name);

        Fi fi = new Fi(path.toAbsolutePath().toString());
        fi.file().createNewFile();

        downloadFile(plugin.getUrl(), path.toString());
        // if (response.getStatus().code >= 300) {
        // Log.info("Failed to download plugin: " + plugin.url + " "
        // + response.getStatus().code);

        // if (Files.exists(path)) {
        // Files.delete(path);
        // }

        // return;
        // }

        // Log.info("Downloaded plugin with status: " + response.getStatus().code);

        try {
            plugins.remove(plugin.id);

            PluginWrapper loaded = pluginManager.getPlugin(plugin.getId());

            if (loaded != null) {
                pluginManager.unloadPlugin(loaded.getPluginId());

                Log.info("Unloaded plugin: " + plugin.name);
            }
        } catch (Exception e) {
            Log.err("Failed to unload plugin: " + plugin.name, e);
        } finally {
        }

        // byte[] bytes = response.getResult();
        // Log.info("Downloaded plugin size: " + bytes.length + " bytes");
        // fi.writeBytes(bytes);

        // Save updated metadata
        meta
                .putObject(plugin.getName())
                .put("updated_at", updatedAt).put("url", plugin.url);

        Fi metaFile = new Fi(METADATA_PATH.toFile());
        metaFile.file().createNewFile();
        metaFile.writeString(meta.toPrettyString());

        if (!Files.exists(path)) {
            throw new RuntimeException("Plugin file not found: " + path);
        }

        loadPlugin(plugin);
    }

    private void loadPlugin(PluginData plugin) {
        if (plugins.containsKey(plugin.id)) {
            return;
        }

        Path path = Paths.get(PLUGIN_DIR, plugin.name);
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
            } else {
                Log.info("Invalid plugin: " + instance.getClass().getName());
            }

            Log.info("Plugin updated and reloaded: " + plugin.name);

        } catch (PluginRuntimeException e) {
            plugins.remove(plugin.id);
            path.toFile().delete();
        } catch (Exception e) {
            plugins.remove(plugin.id);
            Log.err(e);
            throw new RuntimeException("Failed to load plugin: " + plugin.name, e);
        }
    }
}
