package graph.compile;

import java.util.Set;

import graph.format.ValidationResult;

public record ThreadCheckResult(ValidationResult result, Set<String> asyncDispatchNodes,
                                Set<String> pureNodes) {

    public boolean requiresAsyncDispatch(String nodeId) {
        return asyncDispatchNodes.contains(nodeId);
    }

    public boolean isPure(String nodeId) {
        return pureNodes.contains(nodeId);
    }
}
