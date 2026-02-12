package plugin.core;

import arc.util.Log;
import mindustry.Vars;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.FileWatcher;
import plugin.annotations.Init;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
public class FileWatcherManager {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FileWatcherService");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FileWatcher-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private WatchService watchService;
    private final Map<WatchKey, List<WatcherRegistration>> keys = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    @Init
    public void init() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            executor.submit(this::processEvents);
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
                Log.err("Failed to create directory @", dirToWatch, e);
                return;
            }
        }

        try {
            WatchKey key = dirToWatch.register(watchService, events);

            WatcherRegistration reg = new WatcherRegistration(target, callback, debounceMs, isDirectory);
            keys.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(reg);

            Log.info("[gray]Started watching @", target);
        } catch (IOException e) {
            Log.err("Failed to register watcher for @", target, e);
        }
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
        executor.shutdownNow();
        scheduler.shutdownNow();
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
                    executor.submit(task);
                }
            }
        }
    }
}
