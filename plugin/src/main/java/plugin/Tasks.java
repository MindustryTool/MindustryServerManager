package plugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import arc.util.Log;
import arc.util.Time;

public class Tasks {
    private static final ExecutorService IO_TASK_EXECUTOR = Executors
            .newCachedThreadPool();

    private static final ExecutorService CPU_TASK_EXECUTOR = Executors
            .newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public static void io(String name, Runnable r) {
        if (IO_TASK_EXECUTOR.isShutdown()) {
            return;
        }

        IO_TASK_EXECUTOR.submit(() -> {
            long startedAt = Time.millis();
            try {
                r.run();
            } catch (Exception e) {
                Log.err("Failed to execute io task: " + name, e);
            } finally {
                var elapsed = Time.millis() - startedAt;
                if (elapsed > 3000) {
                    Log.warn("IO task " + name + " took " + elapsed + "ms");
                }
            }
        });
    }

    public static void cpu(String name, Runnable r) {
        if (CPU_TASK_EXECUTOR.isShutdown()) {
            return;
        }

        CPU_TASK_EXECUTOR.submit(() -> {
            long startedAt = Time.millis();
            try {
                r.run();
            } catch (Exception e) {
                Log.err("Failed to execute cpu task: " + name, e);
            } finally {
                var elapsed = Time.millis() - startedAt;
                if (elapsed > 1000) {
                    Log.warn("CPU task " + name + " took " + elapsed + "ms");
                }
            }
        });
    }

    public static void destroy() {
        IO_TASK_EXECUTOR.shutdownNow();
        CPU_TASK_EXECUTOR.shutdownNow();
    }
}
