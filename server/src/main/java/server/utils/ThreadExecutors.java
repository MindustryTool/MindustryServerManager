package server.utils;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import arc.util.Log;

public final class ThreadExecutors {

    private ThreadExecutors() {}

    public static ExecutorService createVirtualThreadExecutor() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            Log.info("Initialized Java 21 Virtual Thread executor");
            return (ExecutorService) method.invoke(null);
        } catch (NoSuchMethodException e) {
            Log.info("Java 21 not detected (running on Java @), falling back to platform threads",
                    System.getProperty("java.version"));
            return Executors.newCachedThreadPool();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create virtual thread executor", e);
        }
    }
}
