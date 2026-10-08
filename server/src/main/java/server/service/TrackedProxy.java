package server.service;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * A usable proxy plus its usage stats. This object is the single source of truth for a
 * proxy's address, sent count, and consecutive failure count.
 */
public final class TrackedProxy {

    private final InetSocketAddress address;
    private int sentCount;
    private int consecutiveFailures;

    TrackedProxy(InetSocketAddress address) {
        this.address = Objects.requireNonNull(address, "address");
    }

    public InetSocketAddress address() {
        return address;
    }

    public int getSentCount() {
        return sentCount;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    void markSent() {
        sentCount++;
    }

    void recordSuccess() {
        consecutiveFailures = 0;
    }

    int recordFailure() {
        return ++consecutiveFailures;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TrackedProxy other && address.equals(other.address);
    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }

    @Override
    public String toString() {
        return address.getHostString() + ":" + address.getPort()
                + " (sent=" + sentCount + ", fails=" + consecutiveFailures + ")";
    }
}
