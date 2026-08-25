package graph.runtime;

public class GraphBudgetExceeded extends RuntimeException {

    private final long spent;
    private final long max;

    public GraphBudgetExceeded(long spent, long max) {
        super("Graph operation budget exceeded: spent " + spent + " > max " + max);
        this.spent = spent;
        this.max = max;
    }

    public long spent() {
        return spent;
    }

    public long max() {
        return max;
    }
}
