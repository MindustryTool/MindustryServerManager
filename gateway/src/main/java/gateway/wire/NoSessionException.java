package gateway.wire;

/**
 * Thrown when an outbound operation is attempted with no open session.
 *
 * <p>
 * Distinct from a response timeout so callers can target the offline case.
 */
public class NoSessionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String requestedType;

    public NoSessionException(String requestedType) {
        super("No open WebSocket session for: " + requestedType);
        this.requestedType = requestedType;
    }

    /** The RPC type or event that could not be sent. */
    public String requestedType() {
        return requestedType;
    }
}
