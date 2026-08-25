package graph.compile;

import java.util.HashSet;
import java.util.Set;

import graph.format.GraphDocument;
import graph.format.ValidationResult;
import graph.registry.ThreadRequirement;

public final class ThreadCheck {

    private ThreadCheck() {
    }

    public static ThreadCheckResult check(GraphDocument document, LinkedGraph linked) {
        Set<String> asyncNodes = new HashSet<>();
        Set<String> pureNodes = new HashSet<>();

        for (var entry : linked.nodes().entrySet()) {
            LinkedGraph.LinkedNode node = entry.getValue();
            if (!node.type().equals("call") || node.function() == null) {
                continue;
            }
            ThreadRequirement requirement = node.function().threadRequirement();
            switch (requirement) {
                case ASYNC -> asyncNodes.add(entry.getKey());
                case PURE, READ_ONLY -> pureNodes.add(entry.getKey());
                default -> {
                }
            }
        }

        return new ThreadCheckResult(ValidationResult.pass(), Set.copyOf(asyncNodes),
                Set.copyOf(pureNodes));
    }
}
