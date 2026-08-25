package graph.format;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GraphParser {

    public static final Set<String> TOP_LEVEL_FIELDS =
            Set.of("version", "id", "revision", "variables", "nodes", "edges", "editor");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ParseOutcome(GraphDocument document, List<Diagnostic> diagnostics) {

        public boolean ok() {
            return document != null && diagnostics.stream().noneMatch(Diagnostic::isError);
        }
    }

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private GraphParser() {
    }

    public static ParseOutcome parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return fatal(Diagnostic.error("E_PARSE", "Invalid JSON: " + e.getOriginalMessage()));
        }
        if (root == null || !root.isObject()) {
            return fatal(Diagnostic.error("E_PARSE", "Document root must be a JSON object"));
        }
        return new GraphParser().parseDocument((ObjectNode) root);
    }

    private static ParseOutcome fatal(Diagnostic diagnostic) {
        return new ParseOutcome(null, List.of(diagnostic));
    }

    private ParseOutcome parseDocument(ObjectNode obj) {
        for (String field : unknownFields(obj)) {
            error("E_UNKNOWN_FIELD", "Unknown top-level field '" + field + "'", field);
        }

        Integer version = readInt(obj, "version", true);
        Long revision = readLong(obj, "revision", false);

        if (version != null && !hasErrors()) {
            if (version != GraphDocument.SUPPORTED_SCHEMA_VERSION) {
                diagnostics.add(Diagnostic.error("E_VERSION_UNSUPPORTED",
                        "Unsupported schema version: found " + version + ", supported ["
                                + GraphDocument.SUPPORTED_SCHEMA_VERSION + "]"));
                return outcome(null);
            }
        }
        if (hasErrors()) {
            return outcome(null);
        }

        String id = readText(obj, "id", true);
        List<VariableDecl> variables = readVariables(obj);
        List<GraphNode> nodes = readNodes(obj);
        List<GraphEdge> edges = readEdges(obj);

        if (hasErrors()) {
            return outcome(null);
        }

        JsonNode editor = obj.get("editor");
        return outcome(new GraphDocument(
                version,
                id,
                revision == null ? 0L : revision,
                variables,
                nodes,
                edges,
                editor));
    }

    private ParseOutcome outcome(GraphDocument document) {
        return new ParseOutcome(document, List.copyOf(diagnostics));
    }

    private boolean hasErrors() {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.isError()) {
                return true;
            }
        }
        return false;
    }

    private void error(String code, String message, String pointer) {
        diagnostics.add(Diagnostic.error(code, message, null, pointer));
    }

    private void errorAt(String code, String message, String nodeId, String pointer) {
        diagnostics.add(Diagnostic.error(code, message, nodeId, pointer));
    }

    private List<String> unknownFields(ObjectNode obj) {
        List<String> unknown = new ArrayList<>();
        obj.fieldNames().forEachRemaining(name -> {
            if (!TOP_LEVEL_FIELDS.contains(name)) {
                unknown.add(name);
            }
        });
        return unknown;
    }

    private Integer readInt(ObjectNode obj, String field, boolean required) {
        return readNumberAs(obj, field, required, node -> node.isInt() ? (Integer) node.asInt() : null,
                "integer");
    }

    private Long readLong(ObjectNode obj, String field, boolean required) {
        return readNumberAs(obj, field, required, node -> node.isIntegralNumber()
                ? node.asLong() : null, "integer");
    }

    private interface NumberReader<T> {
        T read(JsonNode node);
    }

    private <T> T readNumberAs(ObjectNode obj, String field, boolean required,
                               NumberReader<T> reader, String expected) {
        JsonNode node = obj.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                error("E_MISSING_FIELD", "Missing required field '" + field + "'", field);
            }
            return null;
        }
        T value = reader.read(node);
        if (value == null) {
            error("E_INVALID_FIELD", "Field '" + field + "' must be an " + expected, field);
            return null;
        }
        return value;
    }

    private String readText(ObjectNode obj, String field, boolean required) {
        JsonNode node = obj.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                error("E_MISSING_FIELD", "Missing required field '" + field + "'", field);
            }
            return null;
        }
        if (!node.isTextual()) {
            error("E_INVALID_FIELD", "Field '" + field + "' must be a string", field);
            return null;
        }
        return node.asText();
    }

    private ObjectNode readObject(ArrayNode array, int index, String containerField) {
        JsonNode item = array.get(index);
        String pointer = containerField + "[" + index + "]";
        if (!item.isObject()) {
            error("E_INVALID_FIELD", "Entry in '" + containerField + "' must be an object", pointer);
            return null;
        }
        return (ObjectNode) item;
    }

    private List<VariableDecl> readVariables(ObjectNode obj) {
        List<VariableDecl> result = new ArrayList<>();
        JsonNode arr = obj.get("variables");
        if (arr == null) {
            return result;
        }
        if (!arr.isArray()) {
            error("E_INVALID_FIELD", "Field 'variables' must be an array", "variables");
            return result;
        }
        ArrayNode array = (ArrayNode) arr;
        for (int i = 0; i < array.size(); i++) {
            ObjectNode decl = readObject(array, i, "variables");
            if (decl == null) {
                continue;
            }
            String name = readText(decl, "name", true);
            String scope = readText(decl, "scope", true);
            String typeRef = readText(decl, "type", true);
            if (name != null && scope != null && typeRef != null) {
                try {
                    result.add(new VariableDecl(name, scope, typeRef));
                } catch (IllegalArgumentException e) {
                    error("E_INVALID_IDENTIFIER", e.getMessage(), "variables[" + i + "]");
                }
            }
        }
        return result;
    }

    private List<GraphNode> readNodes(ObjectNode obj) {
        List<GraphNode> result = new ArrayList<>();
        JsonNode arr = obj.get("nodes");
        if (arr == null) {
            error("E_MISSING_FIELD", "Missing required field 'nodes'", "nodes");
            return result;
        }
        if (!arr.isArray()) {
            error("E_INVALID_FIELD", "Field 'nodes' must be an array", "nodes");
            return result;
        }
        ArrayNode array = (ArrayNode) arr;
        for (int i = 0; i < array.size(); i++) {
            ObjectNode nodeObj = readObject(array, i, "nodes");
            if (nodeObj == null) {
                continue;
            }
            String nodeId = readText(nodeObj, "id", true);
            String type = readText(nodeObj, "type", true);
            if (nodeId == null || type == null) {
                continue;
            }
            LinkedHashMap<String, JsonNode> data = new LinkedHashMap<>();
            nodeObj.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (!key.equals("id") && !key.equals("type")) {
                    data.put(key, entry.getValue());
                }
            });
            try {
                result.add(GraphNode.of(nodeId, type, data));
            } catch (IllegalArgumentException e) {
                errorAt("E_INVALID_FIELD", e.getMessage(), nodeId, "nodes[" + i + "]");
            }
        }
        return result;
    }

    private List<GraphEdge> readEdges(ObjectNode obj) {
        List<GraphEdge> result = new ArrayList<>();
        JsonNode arr = obj.get("edges");
        if (arr == null) {
            return result;
        }
        if (!arr.isArray()) {
            error("E_INVALID_FIELD", "Field 'edges' must be an array", "edges");
            return result;
        }
        ArrayNode array = (ArrayNode) arr;
        for (int i = 0; i < array.size(); i++) {
            ObjectNode edgeObj = readObject(array, i, "edges");
            if (edgeObj == null) {
                continue;
            }
            String from = readText(edgeObj, "from", true);
            String to = readText(edgeObj, "to", true);
            if (from != null && to != null) {
                try {
                    result.add(GraphEdge.of(from, to));
                } catch (IllegalArgumentException e) {
                    error("E_INVALID_PORT_ADDRESS", e.getMessage(), "edges[" + i + "]");
                }
            }
        }
        return result;
    }
}
