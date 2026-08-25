package graph.format;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class GraphNode {

    private final String id;
    private final String type;
    private final Map<String, JsonNode> data;

    private GraphNode(String id, String type, Map<String, JsonNode> data) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public static GraphNode of(String id, String type) {
        return new GraphNode(id, type, Map.of());
    }

    public static GraphNode of(String id, String type, Map<String, JsonNode> data) {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : data.entrySet()) {
            if (e.getKey().equals("id") || e.getKey().equals("type")) {
                continue;
            }
            copy.put(e.getKey(), e.getValue());
        }
        return new GraphNode(id, type, copy);
    }

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public Map<String, JsonNode> data() {
        return data;
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    public JsonNode get(String key) {
        return data.get(key);
    }

    public String getString(String key) {
        JsonNode node = data.get(key);
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(
                    "Node '" + id + "' (" + type + ") is missing text field '" + key + "'");
        }
        return node.asText();
    }

    public int getInt(String key) {
        JsonNode node = data.get(key);
        if (node == null || !node.canConvertToInt()) {
            throw new IllegalArgumentException(
                    "Node '" + id + "' (" + type + ") is missing int field '" + key + "'");
        }
        return node.asInt();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GraphNode other)) {
            return false;
        }
        return id.equals(other.id) && type.equals(other.type) && data.equals(other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, data);
    }

    @Override
    public String toString() {
        return "GraphNode[" + id + ":" + type + "]";
    }
}
