package graph.format;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

public final class GraphDocument {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final int version;
    private final String id;
    private final long revision;
    private final List<VariableDecl> variables;
    private final List<GraphNode> nodes;
    private final List<GraphEdge> edges;
    private final JsonNode editor;

    public GraphDocument(
            int version,
            String id,
            long revision,
            List<VariableDecl> variables,
            List<GraphNode> nodes,
            List<GraphEdge> edges,
            JsonNode editor) {
        this.version = version;
        this.id = Objects.requireNonNull(id, "id");
        this.revision = revision;
        this.variables = List.copyOf(variables);
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.editor = editor;
    }

    public static GraphDocument initial(String id, List<VariableDecl> variables,
                                        List<GraphNode> nodes, List<GraphEdge> edges,
                                        JsonNode editor) {
        return new GraphDocument(SUPPORTED_SCHEMA_VERSION, id, 0, variables, nodes, edges, editor);
    }

    public int version() {
        return version;
    }

    public String id() {
        return id;
    }

    public long revision() {
        return revision;
    }

    public List<VariableDecl> variables() {
        return variables;
    }

    public List<GraphNode> nodes() {
        return nodes;
    }

    public List<GraphEdge> edges() {
        return edges;
    }

    public JsonNode editor() {
        return editor;
    }

    public GraphDocument withRevision(long newRevision) {
        return new GraphDocument(version, id, newRevision, variables, nodes, edges, editor);
    }

    public GraphNode node(String nodeId) {
        for (GraphNode node : nodes) {
            if (node.id().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "GraphDocument[" + id + "@v" + version + ",rev=" + revision
                + ",nodes=" + nodes.size() + ",edges=" + edges.size() + "]";
    }
}
