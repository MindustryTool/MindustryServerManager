package plugin.core;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import arc.Core;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.TaskQueue;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.annotations.Schedule;

@Component
public class TaskQueueMonitor {

    public static final int DEFAULT_THRESHOLD = 200;
    private static final Pattern LAMBDA_CLEANUP_PATTERN = Pattern.compile("\\$\\$Lambda.*|/.*");

    private TaskQueue taskQueue;
    private Field innerRunnablesField;
    private final int threshold;

    public TaskQueueMonitor() {
        this.threshold = DEFAULT_THRESHOLD;
    }

    TaskQueueMonitor(TaskQueue taskQueue, Field innerRunnablesField, int threshold) {
        this.taskQueue = taskQueue;
        this.innerRunnablesField = innerRunnablesField;
        this.threshold = threshold;
    }

    @Init
    public void init() {
        if (taskQueue != null && innerRunnablesField != null) {
            return;
        }

        if (Core.app == null) {
            throw new IllegalStateException("Core.app is null during TaskQueueMonitor initialization");
        }

        Field appRunnablesField = findField(Core.app.getClass(), "runnables");
        if (appRunnablesField == null) {
            throw new IllegalStateException("Could not find 'runnables' field in " + Core.app.getClass().getName());
        }

        try {
            Object queueObj = appRunnablesField.get(Core.app);
            if (!(queueObj instanceof TaskQueue queue)) {
                throw new IllegalStateException("Field 'runnables' in " + Core.app.getClass().getName()
                        + " is not an instance of TaskQueue: "
                        + (queueObj == null ? "null" : queueObj.getClass().getName()));
            }
            this.taskQueue = queue;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to access 'runnables' field in " + Core.app.getClass().getName(), e);
        }

        Field innerField = findField(TaskQueue.class, "runnables");
        if (innerField == null) {
            throw new IllegalStateException("Could not find 'runnables' field in TaskQueue");
        }
        this.innerRunnablesField = innerField;
    }

    @Schedule(delay = 3, fixedRate = 3, unit = TimeUnit.SECONDS)
    public void checkQueueSize() {
        if (taskQueue == null || innerRunnablesField == null) {
            return;
        }

        int size = taskQueue.size();
        if (size <= threshold) {
            return;
        }

        List<Runnable> snapshot;
        try {
            @SuppressWarnings("unchecked")
            Seq<Runnable> runnables = (Seq<Runnable>) innerRunnablesField.get(taskQueue);
            if (runnables == null) {
                return;
            }

            synchronized (runnables) {
                snapshot = runnables.list();
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to read inner runnables from TaskQueue", e);
        }

        Map<String, Long> histogram = computeHistogram(snapshot);

        Log.warn("Core.app TaskQueue spiked to @ tasks! Breakdown:", size);
        histogram.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> Log.warn(" - @ : @ tasks", entry.getKey(), entry.getValue()));
    }

    public static Map<String, Long> computeHistogram(List<Runnable> runnables) {
        return runnables.stream()
                .collect(Collectors.groupingBy(
                        TaskQueueMonitor::resolveOrigin,
                        Collectors.counting()));
    }

    public static String resolveOrigin(Runnable runnable) {
        if (runnable == null) {
            return "null";
        }
        return LAMBDA_CLEANUP_PATTERN.matcher(runnable.getClass().getName()).replaceAll("");
    }

    static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Walk up class hierarchy to find field declared on superclasses
                current = current.getSuperclass();
            }
        }
        return null;
    }

    TaskQueue getTaskQueue() {
        return taskQueue;
    }

    Field getInnerRunnablesField() {
        return innerRunnablesField;
    }

    int getThreshold() {
        return threshold;
    }
}
