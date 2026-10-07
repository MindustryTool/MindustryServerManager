package gateway.wire;

/**
 * Event-stream listen payload. The event name travels in the frame
 * {@code event} field, never here.
 */
public record SubscriptionPayload(
        Object data) {
}
