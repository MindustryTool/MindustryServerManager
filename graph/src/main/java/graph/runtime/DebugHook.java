package graph.runtime;

import java.util.Map;

/**
 * Debug telemetry sink installed on the engine. All methods have safe
 * defaults; the engine only calls them when a hook is installed.
 */
public interface DebugHook {

    default void onNodeEnter(long executionId, String graphId, int generation,
            String nodeId, Map<String, Object> variableSnapshot) {
    }

    default void onExecutionEvent(long executionId, String graphId,
            String event, String detailNodeId, Map<String, Object> variables) {
    }
}
