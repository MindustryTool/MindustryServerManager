package graph.runtime;

public final class GraphReturnSignal extends RuntimeException {

    private final Object value;

    public GraphReturnSignal(Object value) {
        super(null, null, false, false);
        this.value = value;
    }

    public Object value() {
        return value;
    }
}
