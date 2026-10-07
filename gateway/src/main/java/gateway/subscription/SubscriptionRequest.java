package gateway.subscription;

import java.util.Objects;

public record SubscriptionRequest<Params>(Params params, SubscriptionHandle handle) {
    public SubscriptionRequest {
        Objects.requireNonNull(handle, "handle");
    }
}
