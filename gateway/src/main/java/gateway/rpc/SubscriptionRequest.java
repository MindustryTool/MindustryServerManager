package gateway.rpc;

import java.util.Objects;

public record SubscriptionRequest<Params>(Params params, PushHandle handle) {
    public SubscriptionRequest {
        Objects.requireNonNull(handle, "handle");
    }
}
