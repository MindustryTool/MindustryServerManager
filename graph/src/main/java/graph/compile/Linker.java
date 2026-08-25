package graph.compile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graph.format.Diagnostic;
import graph.format.GraphDocument;
import graph.format.GraphNode;
import graph.registry.EventDescriptor;
import graph.registry.FunctionDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.Overload;
import graph.registry.PropertyDescriptor;

public final class Linker {

    private final GraphRegistry registry;

    public Linker(GraphRegistry registry) {
        this.registry = registry;
    }

    public record LinkResult(LinkedGraph graph, List<Diagnostic> diagnostics) {

        public boolean ok() {
            return graph != null && diagnostics.stream().noneMatch(Diagnostic::isError);
        }
    }

    public LinkResult link(GraphDocument document) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, LinkedGraph.LinkedNode> linked = new HashMap<>();
        Set<String> consumed = new HashSet<>();

        for (GraphNode node : document.nodes()) {
            switch (node.type()) {
                case "call" -> linkCall(node, linked, consumed, diagnostics);
                case "event" -> linkEvent(node, linked, consumed, diagnostics);
                case "get-property", "set-property" ->
                        linkProperty(node, linked, consumed, diagnostics);
                case "get-variable", "set-variable" -> linkVariable(document, node, linked, diagnostics);
                default -> linked.put(node.id(), LinkedGraph.LinkedNode.plain(node.id(), node.type()));
            }
        }

        if (diagnostics.stream().anyMatch(Diagnostic::isError)) {
            return new LinkResult(null, diagnostics);
        }
        String fingerprint = registry.fingerprint(consumed);
        return new LinkResult(new LinkedGraph(document, linked, consumed, fingerprint), diagnostics);
    }

    private void linkCall(GraphNode node, Map<String, LinkedGraph.LinkedNode> linked,
                          Set<String> consumed, List<Diagnostic> diagnostics) {
        String functionId;
        try {
            functionId = node.getString("function");
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error("E_MISSING_FIELD",
                    "Call node requires 'function' field", node.id(), "function"));
            return;
        }
        if (!registry.hasFunction(functionId)) {
            diagnostics.add(Diagnostic.error("E_UNKNOWN_FUNCTION",
                    "Unknown function '" + functionId + "'", node.id(), "function"));
            return;
        }
        FunctionDescriptor descriptor;
        try {
            descriptor = registry.function(functionId);
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error("E_UNKNOWN_FUNCTION",
                    e.getMessage(), node.id(), "function"));
            return;
        }

        String overloadHash = node.has("overload") ? node.getString("overload") : null;
        Overload selected = selectOverload(node, functionId, descriptor, overloadHash, diagnostics);
        if (selected == null) {
            return;
        }

        consumed.add(functionId);
        linked.put(node.id(), LinkedGraph.LinkedNode.forFunction(node.id(), descriptor, selected));
    }

    private Overload selectOverload(GraphNode node, String functionId,
                                    FunctionDescriptor descriptor, String overloadHash,
                                    List<Diagnostic> diagnostics) {
        if (overloadHash != null) {
            for (Overload overload : descriptor.overloads()) {
                if (overload.hash().equals(overloadHash)) {
                    return overload;
                }
            }
            diagnostics.add(Diagnostic.error("E_OVERLOAD_NOT_FOUND",
                    "Function '" + functionId + "' has no overload with hash '" + overloadHash
                            + "' (available: " + descriptor.overloads() + ")",
                    node.id(), "overload"));
            return null;
        }
        if (descriptor.overloads().size() == 1) {
            return descriptor.overloads().get(0);
        }
        diagnostics.add(Diagnostic.error("E_AMBIGUOUS_OVERLOAD",
                "Function '" + functionId + "' has " + descriptor.overloads().size()
                        + " overloads; specify an 'overload' hash",
                node.id(), "overload"));
        return null;
    }

    private void linkEvent(GraphNode node, Map<String, LinkedGraph.LinkedNode> linked,
                           Set<String> consumed, List<Diagnostic> diagnostics) {
        String eventId;
        try {
            eventId = node.getString("event");
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error("E_MISSING_FIELD",
                    "Event node requires 'event' field", node.id(), "event"));
            return;
        }
        EventDescriptor event = registry.event(eventId);
        if (event == null) {
            diagnostics.add(Diagnostic.error("E_UNKNOWN_EVENT",
                    "Unknown event '" + eventId + "'", node.id(), "event"));
            return;
        }
        consumed.add(eventId);
        linked.put(node.id(), LinkedGraph.LinkedNode.forEvent(node.id(), event));
    }

    private void linkProperty(GraphNode node, Map<String, LinkedGraph.LinkedNode> linked,
                              Set<String> consumed, List<Diagnostic> diagnostics) {
        String propertyId;
        try {
            propertyId = node.getString("property");
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error("E_MISSING_FIELD",
                    "Property node requires 'property' field", node.id(), "property"));
            return;
        }
        PropertyDescriptor property = registry.property(propertyId);
        if (property == null) {
            diagnostics.add(Diagnostic.error("E_UNKNOWN_PROPERTY",
                    "Unknown property '" + propertyId + "'", node.id(), "property"));
            return;
        }
        boolean isSet = node.type().equals("set-property");
        if (isSet && !property.writable()) {
            diagnostics.add(Diagnostic.error("E_PROPERTY_READ_ONLY",
                    "Property '" + propertyId + "' is not writable", node.id(), "property"));
            return;
        }
        consumed.add(propertyId);
        linked.put(node.id(),
                LinkedGraph.LinkedNode.forProperty(node.id(), node.type(), property));
    }

    private void linkVariable(GraphDocument document, GraphNode node,
                              Map<String, LinkedGraph.LinkedNode> linked,
                              List<Diagnostic> diagnostics) {
        String variableName;
        try {
            variableName = node.getString("variable");
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error("E_MISSING_FIELD",
                    "Variable node requires 'variable' field", node.id(), "variable"));
            return;
        }
        boolean known = document.variables().stream()
                .anyMatch(v -> v.name().equals(variableName));
        if (!known) {
            diagnostics.add(Diagnostic.error("E_UNKNOWN_VARIABLE",
                    "Unknown variable '" + variableName + "'", node.id(), "variable"));
            return;
        }
        linked.put(node.id(),
                LinkedGraph.LinkedNode.forVariable(node.id(), node.type(), variableName));
    }
}

