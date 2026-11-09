package plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import arc.util.Log;

public class Tasks {
    private static final ExecutorService IO_TASK_EXECUTOR = Executors
            .newCachedThreadPool();

    public static void io(String name, Runnable r) {
        io(name, () -> {
            r.run();
            return null;
        });
    }

    public static <T> CompletableFuture<T> io(String name, Supplier<T> supplier) {
        if (IO_TASK_EXECUTOR.isShutdown()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception e) {
                Log.err("Failed to execute io task: " + name, e);
                throw e;
            }
        }, IO_TASK_EXECUTOR);
    }

    public static void cpu(String name, Runnable r) {
        io(name, r);
    }

    public static void destroy() {
        IO_TASK_EXECUTOR.shutdownNow();
    }
}
