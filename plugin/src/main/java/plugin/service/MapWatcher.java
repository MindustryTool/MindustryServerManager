package plugin.service;

import java.io.File;
import java.nio.file.*;

import arc.util.Log;
import mindustry.Vars;
import mindustry.maps.Maps;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.core.FileWatcherManager;

import static arc.util.Log.info;

@Component
public class MapWatcher {
    File mapFolder = Vars.customMapDirectory.file();
    private final FileWatcherManager fileWatcher;

    public MapWatcher(FileWatcherManager fileWatcher) {
        this.fileWatcher = fileWatcher;
    }

    @Init
    public void init() {
        fileWatcher.watch(mapFolder.toPath(), this::onMapChange, 1000,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
    }

    private void onMapChange(Path changed) {
        try {
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
        } catch (Exception e) {
            Log.err("MapWatcher failed to reload maps", e);
        }
    }
}
