package plugin.core;

import arc.util.Log;
import mindustry.Vars;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.FileWatcher;
import plugin.annotations.Init;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class FileWatcherManager {
    private Thread watcherThread;
    private ScheduledExecutorService scheduler;
    private WatchService watchService;
    private final Map<WatchKey, List<WatcherRegistration>> keys = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private final AtomicBoolean started = new AtomicBoolean(false);

    @Init
    public void init() {
        // Initialization deferred until a watcher is actually registered
    }

    private synchronized void ensureStarted() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FileWatcher-Scheduler");
                t.setDaemon(true);
                return t;
            });
            watcherThread = new Thread(this::processEvents, "FileWatcherService");
            watcherThread.setDaemon(true);
            watcherThread.start();
        } catch (IOException e) {
            Log.err("Failed to initialize FileWatcherService", e);
        }
    }

    public void process(FileWatcher annotation, Object instance, Method method) {
        String pathStr = annotation.value();
        Path path;
        if (Paths.get(pathStr).isAbsolute()) {
            path = Paths.get(pathStr);
        } else {
            path = Vars.dataDirectory.file().toPath().resolve(pathStr);
        }

        watch(path, p -> {
            try {
                method.setAccessible(true);
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == Path.class) {
                    method.invoke(instance, p);
                } else if (method.getParameterCount() == 0) {
                    method.invoke(instance);
                } else {
                    Log.err("Method @ annotated with @FileWatcher must have 0 arguments or 1 argument of type Path",
                            method.getName());
                }
            } catch (Exception e) {
                Log.err("Failed to invoke @FileWatcher on " + instance.getClass().getName(), e);
            }
        }, 1000, StandardWatchEventKinds.ENTRY_MODIFY);
    }

    public void watch(Path target, Consumer<Path> callback, long debounceMs, WatchEvent.Kind<?>... events) {
        if (target == null)
            return;

        ensureStarted();
        if (scheduler == null) {
            return;
        }

        scheduler.execute(() -> {
            Path dirToWatch;
            boolean isDirectory;

            if (Files.isDirectory(target)) {
                dirToWatch = target;
                isDirectory = true;
            } else {
                dirToWatch = target.getParent();
                if (dirToWatch == null)
                    dirToWatch = target;
                isDirectory = false;
            }

            if (!Files.exists(dirToWatch)) {
                try {
                    Files.createDirectories(dirToWatch);
                } catch (IOException e) {
                    Log.err("Failed to create directory" + dirToWatch, e);
                    return;
                }
            }

            try {
                WatchKey key = dirToWatch.register(watchService, events);

                WatcherRegistration reg = new WatcherRegistration(target, callback, debounceMs, isDirectory);
                keys.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(reg);

                Log.debug("[gray]Started watching @", target);
            } catch (IOException e) {
                Log.err("Failed to register watcher for @", target, e);
            }
        });
    }

    private void processEvents() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                List<WatcherRegistration> regs = keys.get(key);

                if (regs != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW)
                            continue;

                        Path filename = (Path) event.context();

                        for (WatcherRegistration reg : regs) {
                            if (reg.matches(filename)) {
                                reg.trigger(filename);
                            }
                        }
                    }
                }

                if (!key.reset()) {
                    keys.remove(key);
                }
            } catch (InterruptedException e) {
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                Log.err("Error in FileWatcherService loop", e);
            }
        }
    }

    @Destroy
    public void destroy() {
        running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        try {
            if (watchService != null)
                watchService.close();
        } catch (IOException e) {
            // ignore
        }
    }

    private class WatcherRegistration {
        final Path target;
        final Consumer<Path> callback;
        final long debounceMs;
        final boolean isDirectory;

        private ScheduledFuture<?> pendingTask;
        private final Object lock = new Object();

        WatcherRegistration(Path target, Consumer<Path> callback, long debounceMs, boolean isDirectory) {
            this.target = target;
            this.callback = callback;
            this.debounceMs = debounceMs;
            this.isDirectory = isDirectory;
        }

        boolean matches(Path changedFile) {
            if (isDirectory)
                return true;
            return target.getFileName().equals(changedFile);
        }

        void trigger(Path changedFile) {
            synchronized (lock) {
                if (pendingTask != null && !pendingTask.isDone()) {
                    pendingTask.cancel(false);
                }

                Runnable task = () -> {
                    try {
                        callback.accept(isDirectory ? target.resolve(changedFile) : target);
                    } catch (Exception e) {
                        Log.err("Error in watcher callback for @", target, e);
                    }
                };

                if (debounceMs > 0) {
                    pendingTask = scheduler.schedule(task, debounceMs, TimeUnit.MILLISECONDS);
                } else {
                    scheduler.execute(task);
                }
            }
        }
    }
}
