package graph.compile;

import java.util.Map;
import java.util.Set;

import graph.format.GraphDocument;
import graph.registry.EventDescriptor;
import graph.registry.FunctionDescriptor;
import graph.registry.Overload;
import graph.registry.PropertyDescriptor;

public final class LinkedGraph {

    private final GraphDocument document;
    private final Map<String, LinkedNode> nodes;
    private final Set<String> consumedIds;
    private final String registryFingerprint;

    public LinkedGraph(GraphDocument document, Map<String, LinkedNode> nodes,
                       Set<String> consumedIds, String registryFingerprint) {
        this.document = document;
        this.nodes = Map.copyOf(nodes);
        this.consumedIds = Set.copyOf(consumedIds);
        this.registryFingerprint = registryFingerprint;
    }

    public GraphDocument document() {
        return document;
    }

    public Map<String, LinkedNode> nodes() {
        return nodes;
    }

    public LinkedNode node(String nodeId) {
        return nodes.get(nodeId);
    }

    public Set<String> consumedIds() {
        return consumedIds;
    }

    public String registryFingerprint() {
        return registryFingerprint;
    }

    public record LinkedNode(
            String nodeId,
            String type,
            FunctionDescriptor function,
            Overload overload,
            EventDescriptor event,
            PropertyDescriptor property,
            String variableName) {

        public static LinkedNode forFunction(String nodeId, FunctionDescriptor fn, Overload overload) {
            return new LinkedNode(nodeId, "call", fn, overload, null, null, null);
        }

        public static LinkedNode forEvent(String nodeId, EventDescriptor event) {
            return new LinkedNode(nodeId, "event", null, null, event, null, null);
        }

        public static LinkedNode forProperty(String nodeId, String type, PropertyDescriptor property) {
            return new LinkedNode(nodeId, type, null, null, null, property, null);
        }

        public static LinkedNode forVariable(String nodeId, String type, String variableName) {
            return new LinkedNode(nodeId, type, null, null, null, null, variableName);
        }

        public static LinkedNode plain(String nodeId, String type) {
            return new LinkedNode(nodeId, type, null, null, null, null, null);
        }
    }
}
