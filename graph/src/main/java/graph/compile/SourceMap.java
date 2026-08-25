package graph.compile;

import java.util.ArrayList;
import java.util.List;

public record SourceMap(String graphId, String className, List<Mapping> mappings) {

    public SourceMap {
        mappings = List.copyOf(mappings);
    }

    public record Mapping(int lineStart, int lineEnd, String nodeId, String functionId) {

    }

    public SourceMap withClassName(String qualifiedName) {
        return new SourceMap(graphId, qualifiedName, mappings);
    }

    public static final class Builder {
        private final String graphId;
        private final String className;
        private final List<Mapping> mappings = new ArrayList<>();
        private int pendingStart = -1;
        private String pendingNode;
        private String pendingFunction;
        private int currentLine = 1;

        public Builder(String graphId, String className) {
            this.graphId = graphId;
            this.className = className;
        }

        public void newline() {
            currentLine++;
            closePending();
        }

        private void closePending() {
            if (pendingStart > 0) {
                mappings.add(new Mapping(pendingStart, currentLine - 1,
                        pendingNode, pendingFunction));
                pendingStart = -1;
                pendingNode = null;
                pendingFunction = null;
            }
        }

        public void markNode(String nodeId, String functionId) {
            closePending();
            if (pendingStart < 0) {
                pendingStart = currentLine;
                pendingNode = nodeId;
                pendingFunction = functionId;
            }
        }

        public SourceMap build() {
            closePending();
            return new SourceMap(graphId, className, mappings);
        }
    }
}
