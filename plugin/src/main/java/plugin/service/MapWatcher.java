package plugin.service;

import java.io.File;
import java.nio.file.*;
import java.util.concurrent.*;

import arc.util.Log;
import mindustry.Vars;
import mindustry.maps.Maps;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;

import static arc.util.Log.info;

@Component
public class MapWatcher {
    File mapFolder = Vars.customMapDirectory.file();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MapWatcher");
        t.setDaemon(true);
        return t;
    });

    @Init
    public void init() {
        executor.submit(() -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                Path dir = mapFolder.toPath();
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                }

                dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);

                info("MapWatcher started watching @", dir);

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key;
                    try {
                        key = watcher.take();
                    } catch (InterruptedException x) {
                        return;
                    }

                    boolean changed = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW)
                            continue;

                        changed = true;
                    }

                    if (changed) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }

                        key.pollEvents();

                        Maps maps = Vars.maps;
                        int beforeMaps = maps.all().size;
                        maps.reload();

                        if (maps.all().size > beforeMaps) {
                            info("[sky]@ new map(s) found and reloaded.", maps.all().size - beforeMaps);
                        } else if (maps.all().size < beforeMaps) {
                            info("[sky]@ old map(s) deleted.", beforeMaps - maps.all().size);
                        } else {
                            info("[sky]Maps reloaded.");
                        }
                    }

                    if (!key.reset())
                        break;
                }
            } catch (Exception e) {
                Log.err("MapWatcher failed", e);
            }
        });
    }

    @Destroy
    public void destroy() {
        executor.shutdownNow();
    }
}
