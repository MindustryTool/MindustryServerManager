package graph.runtime;

import java.util.Map;
import java.util.concurrent.Future;

public interface RuntimeServices {

    Object invokeFunction(String functionId, String overloadHash, Object[] args,
                          InvocationContext ctx) throws Exception;

    Object getVariable(String scope, String name);

    void setVariable(String scope, String name, Object value);

    void scheduleResume(double seconds, Runnable continuation);

    void postToMain(Runnable runnable);

    /**
     * Registers completion of the future. Exactly one of {@code value}/{@code error}
     * is delivered to the callback, which must store it and repost onto the main thread.
     */
    void awaitFuture(java.util.concurrent.CompletableFuture<?> future,
                     java.util.function.BiConsumer<Object, Throwable> done);

    default void awaitWithTimeout(java.util.concurrent.CompletableFuture<?> future,
                                  double seconds,
                                  java.util.function.BiConsumer<Object, Throwable> done) {
        throw new UnsupportedOperationException("await timeout not wired yet");
    }

    java.util.concurrent.CompletableFuture<?> dispatchAsync(String functionId, String overloadHash, Object[] args,
                            InvocationContext ctx);

    java.util.concurrent.CompletableFuture<?> httpAsync(String method, String url,
                        Map<String, String> headers,
                        Map<String, String> query, String body,
                        InvocationContext ctx);

    java.util.concurrent.CompletableFuture<?> dbQueryAsync(String sql, Map<String, Object> params,
                           InvocationContext ctx);

    java.util.concurrent.CompletableFuture<?> dbUpdateAsync(String kind, String table,
                            Map<String, Object> row,
                            InvocationContext ctx);

    void log(String message);

    /**
     * Debug prologue invoked before every statement. Detached default is a
     * no-op so the fast path costs one virtual call.
     */
    default void debugNode(String nodeId) {
    }

    /** Wall-time spent executing a synchronous node body. */
    default void recordNodeTiming(String nodeId, long nanos) {
    }

    default Object startSchedule(String mode, double seconds, Runnable onFire,
                                 InvocationContext ctx) {
        throw new UnsupportedOperationException("schedule services not wired yet");
    }

    default void cancelSchedule(Object handle) {
        throw new UnsupportedOperationException("schedule services not wired yet");
    }

    record HttpResult(int status, Map<String, String> headers, String body, boolean success) {

        public static HttpResult failure(int status, String message) {
            return new HttpResult(status, Map.of(), message == null ? "" : message, false);
        }
    }
}


