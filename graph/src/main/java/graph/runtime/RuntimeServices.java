package graph.runtime;

import java.util.Map;
import java.util.concurrent.Future;

public interface RuntimeServices {

    Object invokeFunction(String functionId, String overloadHash, Object[] args,
                          InvocationContext ctx) throws Exception;

    void scheduleResume(double seconds, Runnable continuation);

    void postToMain(Runnable runnable);

    Object awaitFuture(Future<?> future, int resumeSlot, Runnable continuation);

    Future<?> dispatchAsync(String functionId, String overloadHash, Object[] args,
                            InvocationContext ctx);

    Future<?> httpAsync(String method, String url,
                        Map<String, String> headers,
                        Map<String, String> query, String body,
                        InvocationContext ctx);

    Future<?> dbQueryAsync(String sql, Map<String, Object> params,
                           InvocationContext ctx);

    Future<?> dbUpdateAsync(String kind, String table,
                            Map<String, Object> row,
                            InvocationContext ctx);

    VariableStore variables();

    void log(String message);

    record HttpResult(int status, Map<String, String> headers, String body, boolean success) {

        public static HttpResult failure(int status, String error) {
            return new HttpResult(status, Map.of(), "", false);
        }
    }
}
