package graph.format;

import java.util.Objects;

public record Diagnostic(Severity severity, String code, String message, String nodeId, String pointer) {

    public enum Severity { ERROR, WARNING }

    public Diagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public static Diagnostic error(String code, String message) {
        return new Diagnostic(Severity.ERROR, code, message, null, null);
    }

    public static Diagnostic error(String code, String message, String nodeId) {
        return new Diagnostic(Severity.ERROR, code, message, nodeId, null);
    }

    public static Diagnostic error(String code, String message, String nodeId, String pointer) {
        return new Diagnostic(Severity.ERROR, code, message, nodeId, pointer);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity).append(' ').append(code).append(": ").append(message);
        if (nodeId != null) {
            sb.append(" [node=").append(nodeId).append(']');
        }
        if (pointer != null) {
            sb.append(" @").append(pointer);
        }
        return sb.toString();
    }
}
