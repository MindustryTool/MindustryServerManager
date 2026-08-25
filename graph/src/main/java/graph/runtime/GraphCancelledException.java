package graph.runtime;

public class GraphCancelledException extends RuntimeException {

    private final String reason;

    public GraphCancelledException(String reason) {
        super(reason == null ? "Graph execution cancelled" : "Graph execution cancelled: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
