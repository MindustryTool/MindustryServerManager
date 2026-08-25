package graph.runtime;

public final class CancellationToken {

    private volatile boolean cancelled;
    private volatile String reason;

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel(String why) {
        this.cancelled = true;
        this.reason = why;
    }

    public String reason() {
        return reason;
    }

    public void throwIfCancelled() {
        if (cancelled) {
            throw new GraphCancelledException(reason);
        }
    }
}
