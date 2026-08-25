package graph.format;

import java.util.Objects;

public record PortAddress(String nodeId, String port) {

    public PortAddress {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(port, "port");
        if (nodeId.isEmpty()) {
            throw new IllegalArgumentException("nodeId must not be empty");
        }
        if (port.isEmpty()) {
            throw new IllegalArgumentException("port must not be empty");
        }
    }

    public static boolean isIdentifier(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(text.charAt(0))) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            if (!Character.isJavaIdentifierPart(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static PortAddress parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        int idx = raw.indexOf('.');
        if (idx <= 0 || idx == raw.length() - 1) {
            throw new IllegalArgumentException("Invalid port address: '" + raw + "'");
        }
        String node = raw.substring(0, idx);
        String port = raw.substring(idx + 1);
        if (!isIdentifier(node) || !isIdentifier(port) || raw.indexOf('.', idx + 1) >= 0) {
            throw new IllegalArgumentException("Invalid port address: '" + raw + "'");
        }
        return new PortAddress(node, port);
    }

    public String print() {
        return nodeId + "." + port;
    }

    @Override
    public String toString() {
        return print();
    }
}
