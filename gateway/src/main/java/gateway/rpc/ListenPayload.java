package gateway.rpc;

/**
 * Event-stream listen payload. The event name travels in the frame
 * {@code event} field, never here.
 */
public record ListenPayload(
        Object data) {
}
