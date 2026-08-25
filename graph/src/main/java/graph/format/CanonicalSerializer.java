package graph.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CanonicalSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalSerializer() {
    }

    public static String serialize(GraphDocument doc) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", doc.version());
        root.put("id", doc.id());

        ArrayNode variables = root.putArray("variables");
        List<VariableDecl> sortedVariables = new ArrayList<>(doc.variables());
        sortedVariables.sort(Comparator.comparing(VariableDecl::name));
        for (VariableDecl variable : sortedVariables) {
            ObjectNode decl = variables.addObject();
            decl.put("name", variable.name());
            decl.put("scope", variable.scope());
            decl.put("type", variable.typeRef());
        }

        ArrayNode nodes = root.putArray("nodes");
        List<GraphNode> sortedNodes = new ArrayList<>(doc.nodes());
        sortedNodes.sort(Comparator.comparing(GraphNode::id));
        for (GraphNode node : sortedNodes) {
            ObjectNode nodeJson = nodes.addObject();
            nodeJson.put("id", node.id());
            nodeJson.put("type", node.type());
            List<String> keys = new ArrayList<>(node.data().keySet());
            keys.sort(Comparator.naturalOrder());
            for (String key : keys) {
                nodeJson.set(key, node.data().get(key));
            }
        }

        ArrayNode edges = root.putArray("edges");
        List<GraphEdge> sortedEdges = new ArrayList<>(doc.edges());
        sortedEdges.sort(Comparator.comparing((GraphEdge e) -> e.from().print())
                .thenComparing(e -> e.to().print()));
        for (GraphEdge edge : sortedEdges) {
            ObjectNode edgeJson = edges.addObject();
            edgeJson.put("from", edge.from().print());
            edgeJson.put("to", edge.to().print());
        }
        return toJsonString(root);
    }

    private static String toJsonString(ObjectNode root) {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Canonical serialization failed", e);
        }
    }
}
