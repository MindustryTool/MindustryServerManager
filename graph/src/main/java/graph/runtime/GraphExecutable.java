package graph.runtime;

import java.util.Map;
import java.util.Set;

public interface GraphExecutable {

    int ABI_VERSION = 1;

    String graphId();

    Set<String> eventNodeIds();

    void execute(String eventNodeId, Map<String, Object> payload,
                 InvocationContext ctx, RuntimeServices services) throws Exception;

    default int abiVersion() {
        return ABI_VERSION;
    }
}
