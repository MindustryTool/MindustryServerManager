package gateway.wire;

/**
 * Thrown when an outbound stream is attempted while the connection already
 * holds the maximum number of concurrent open streams.
 */
public class TooManyStreamsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String requestedType;

    public TooManyStreamsException(String requestedType) {
        super("Too many concurrent streams (max " + WsProtocol.MAX_CONCURRENT_STREAMS + "): " + requestedType);
        this.requestedType = requestedType;
    }

    /** The stream type that could not be opened. */
    public String requestedType() {
        return requestedType;
    }
}
