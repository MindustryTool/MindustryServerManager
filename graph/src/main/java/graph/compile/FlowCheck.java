package graph.compile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graph.format.Diagnostic;
import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.format.ValidationResult;
import graph.registry.ThreadRequirement;

public final class FlowCheck {

    private static final Set<String> ASYNC_BOUNDARY_NODES = Set.of(
            "delay", "await", "schedule", "http-get", "http-post", "http-put",
            "http-delete", "db-query", "db-insert", "db-update", "db-delete", "transaction");

    private static final Set<String> DATA_ONLY_NODE_TYPES = Set.of(
            "get-property", "get-variable", "cast");

    private FlowCheck() {
    }

    public static ValidationResult check(GraphDocument document, LinkedGraph linked,
                                         ThreadCheckResult threads) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, List<String>> execOut = execOutEdges(document, threads);
        Set<String> entryPoints = new HashSet<>();
        for (GraphNode node : document.nodes()) {
            if (node.type().equals("event")) {
                entryPoints.add(node.id());
            }
        }

        detectCycles(document, execOut, entryPoints, linked, diagnostics);
        detectUnreachable(document, execOut, entryPoints, diagnostics);

        return new ValidationResult(diagnostics);
    }

    private static Map<String, List<String>> execOutEdges(GraphDocument document,
                                                          ThreadCheckResult threads) {
        Map<String, List<String>> out = new HashMap<>();
        for (GraphEdge edge : document.edges()) {
            if (!isExecEdge(edge, document, threads)) {
                continue;
            }
            out.computeIfAbsent(edge.from().nodeId(), k -> new ArrayList<>())
                    .add(edge.to().nodeId());
        }
        return out;
    }

    private static boolean isExecEdge(GraphEdge edge, GraphDocument document,
                                      ThreadCheckResult threads) {
        String fromPort = edge.from().port();
        String toPort = edge.to().port();
        boolean fromExec = fromPort.equals("then") || fromPort.equals("else")
                || fromPort.equals("body") || fromPort.startsWith("step");
        boolean toExec = toPort.equals("exec") || toPort.equals("body");
        return fromExec && toExec;
    }

    private static void detectCycles(GraphDocument document, Map<String, List<String>> execOut,
                                     Set<String> entryPoints, LinkedGraph linked,
                                     List<Diagnostic> diagnostics) {
        Map<String, Integer> state = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();

        for (String entry : entryPoints) {
            dfs(entry, execOut, state, stack, document, linked, diagnostics);
        }
        for (GraphNode node : document.nodes()) {
            if (execOut.containsKey(node.id())) {
                dfs(node.id(), execOut, state, stack, document, linked, diagnostics);
            }
        }
    }

    private static void dfs(String nodeId, Map<String, List<String>> execOut,
                            Map<String, Integer> state, Deque<String> stack,
                            GraphDocument document, LinkedGraph linked,
                            List<Diagnostic> diagnostics) {
        Integer current = state.get(nodeId);
        if (current != null && current == 2) {
            return;
        }
        if (current != null && current == 1) {
            reportCycle(stack, nodeId, document, linked, diagnostics);
            return;
        }
        state.put(nodeId, 1);
        stack.push(nodeId);
        List<String> successors = execOut.get(nodeId);
        if (successors != null) {
            for (String successor : successors) {
                dfs(successor, execOut, state, stack, document, linked, diagnostics);
            }
        }
        stack.pop();
        state.put(nodeId, 2);
    }

    private static void reportCycle(Deque<String> stack, String backTo,
                                    GraphDocument document, LinkedGraph linked,
                                    List<Diagnostic> diagnostics) {
        List<String> cycleNodes = new ArrayList<>();
        boolean sawBoundary = false;
        for (String node : stack) {
            cycleNodes.add(node);
            GraphNode graphNode = document.node(node);
            String type = graphNode != null ? graphNode.type() : "";
            if (ASYNC_BOUNDARY_NODES.contains(type)) {
                sawBoundary = true;
            }
            LinkedGraph.LinkedNode ln = linked.node(node);
            if (ln != null && ln.function() != null
                    && ln.function().threadRequirement() == ThreadRequirement.ASYNC) {
                sawBoundary = true;
            }
            if (node.equals(backTo)) {
                break;
            }
        }
        if (!sawBoundary) {
            diagnostics.add(Diagnostic.error("E_SYNC_CYCLE",
                    "Execution loop " + cycleNodes + " contains no async boundary"
                            + " (delay/await/schedule/http/db); it would freeze the main thread",
                    backTo));
        }
    }

    private static void detectUnreachable(GraphDocument document,
                                          Map<String, List<String>> execOut,
                                          Set<String> entryPoints,
                                          List<Diagnostic> diagnostics) {
        Set<String> reachable = new HashSet<>(entryPoints);
        Deque<String> work = new ArrayDeque<>(entryPoints);
        while (!work.isEmpty()) {
            String current = work.pop();
            List<String> successors = execOut.get(current);
            if (successors == null) {
                continue;
            }
            for (String successor : successors) {
                if (reachable.add(successor)) {
                    work.push(successor);
                }
            }
        }
        for (GraphNode node : document.nodes()) {
            if (DATA_ONLY_NODE_TYPES.contains(node.type()) || entryPoints.contains(node.id())) {
                continue;
            }
            boolean needsFlow = !node.type().equals("call")
                    || hasFlowSemantics(node);
            if (needsFlow && !reachable.contains(node.id())) {
                diagnostics.add(Diagnostic.warning("W_UNREACHABLE",
                        "Node is not connected to any execution path", node.id()));
            }
        }
    }

    private static boolean hasFlowSemantics(GraphNode node) {
        return node.has("args");
    }
}
