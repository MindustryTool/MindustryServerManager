package gateway.rpc;

public record SubscribePayload(
        String eventType,
        Object data) {
}
