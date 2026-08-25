package graph.format;

import graph.types.TypeRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SchemaValidator {

    public static final Set<String> NODE_TYPE_VOCABULARY = Set.of(
            "event", "call", "get-property", "set-property", "construct", "cast",
            "if", "switch", "sequence", "loop", "for-each",
            "get-variable", "set-variable", "return",
            "schedule", "delay", "await", "parallel",
            "log", "try-catch-finally", "throw", "retry", "timeout",
            "http-get", "http-post", "http-put", "http-delete",
            "db-query", "db-insert", "db-update", "db-delete", "transaction",
            "code");

    private SchemaValidator() {
    }

    public static ValidationResult validate(GraphDocument doc) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        if (!doc.id().matches("[a-z0-9][a-z0-9_-]*")) {
            diagnostics.add(Diagnostic.error("E_INVALID_IDENTIFIER",
                    "Graph id '" + doc.id() + "' must be kebab-case (lowercase letters, digits, '-' or '_')"));
        }
        if (doc.revision() < 0) {
            diagnostics.add(Diagnostic.error("E_INVALID_FIELD", "Revision must be >= 0"));
        }

        validateVariables(doc, diagnostics);
        validateNodes(doc, diagnostics);
        validateEdges(doc, diagnostics);

        return new ValidationResult(diagnostics);
    }

    private static void validateVariables(GraphDocument doc, List<Diagnostic> diagnostics) {
        Set<String> seen = new HashSet<>();
        for (VariableDecl variable : doc.variables()) {
            if (!seen.add(variable.name())) {
                diagnostics.add(Diagnostic.error("E_DUPLICATE_VARIABLE",
                        "Duplicate variable name '" + variable.name() + "'"));
                continue;
            }
            if (!VariableScope.isValid(variable.scope())) {
                diagnostics.add(Diagnostic.error("E_UNKNOWN_SCOPE",
                        "Unknown scope '" + variable.scope() + "' for variable '"
                                + variable.name() + "', expected one of " + VariableScope.all()));
            } else if (VariableScope.requiresKey(variable.scope())) {
                diagnostics.add(Diagnostic.error("E_MISSING_FIELD",
                        "Scoped variable '" + variable.name()
                                + "' requires a key expression (declared in node payloads)"));
            }
            try {
                TypeRef.parse(variable.typeRef());
            } catch (IllegalArgumentException e) {
                diagnostics.add(Diagnostic.error("E_INVALID_TYPE",
                        "Variable '" + variable.name() + "': " + e.getMessage()));
            }
        }
    }

    private static void validateNodes(GraphDocument doc, List<Diagnostic> diagnostics) {
        Set<String> seen = new HashSet<>();
        for (GraphNode node : doc.nodes()) {
            if (!seen.add(node.id())) {
                diagnostics.add(Diagnostic.error("E_DUPLICATE_NODE_ID",
                        "Duplicate node id '" + node.id() + "'", node.id()));
                continue;
            }
            if (!NODE_TYPE_VOCABULARY.contains(node.type())) {
                diagnostics.add(Diagnostic.error("E_UNKNOWN_NODE_TYPE",
                        "Unknown node type '" + node.type() + "'", node.id(), "type"));
            }
        }
    }

    private static void validateEdges(GraphDocument doc, List<Diagnostic> diagnostics) {
        for (GraphEdge edge : doc.edges()) {
            String fromNode = edge.from().nodeId();
            String toNode = edge.to().nodeId();
            if (doc.node(fromNode) == null) {
                diagnostics.add(Diagnostic.error("E_DANGLING_EDGE",
                        "Edge references unknown source node '" + fromNode + "'", fromNode,
                        edge.from().print() + "->" + edge.to().print()));
            }
            if (doc.node(toNode) == null) {
                diagnostics.add(Diagnostic.error("E_DANGLING_EDGE",
                        "Edge references unknown target node '" + toNode + "'", toNode,
                        edge.from().print() + "->" + edge.to().print()));
            }
        }
    }
}
