package plugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import arc.util.Log;
import arc.util.Time;

public class Tasks {
    private static final ExecutorService IO_TASK_EXECUTOR = Executors
            .newCachedThreadPool();

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
                if (elapsed > 11000) {
                    Log.warn("IO task " + name + " took " + elapsed + "ms");
                }
            }
        });
    }

    public static void cpu(String name, Runnable r) {
        io(name, r);
    }

    public static void destroy() {
        IO_TASK_EXECUTOR.shutdownNow();
    }
}
