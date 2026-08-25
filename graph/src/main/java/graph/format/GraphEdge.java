package graph.format;

import java.util.Objects;

public record GraphEdge(PortAddress from, PortAddress to) {

    public GraphEdge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public static GraphEdge of(String fromRaw, String toRaw) {
        return new GraphEdge(PortAddress.parse(fromRaw), PortAddress.parse(toRaw));
    }

    @Override
    public String toString() {
        return from.print() + "->" + to.print();
    }
}
