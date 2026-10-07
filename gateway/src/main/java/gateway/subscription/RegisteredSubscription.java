package gateway.subscription;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class RegisteredSubscription {
    final Class<?> paramsClass;
    final Function<SubscriptionRequest<Object>, CompletableFuture<Void>> onSubscribe;

    RegisteredSubscription(Class<?> paramsClass,
            Function<SubscriptionRequest<Object>, CompletableFuture<Void>> onSubscribe) {
        this.paramsClass = paramsClass;
        this.onSubscribe = onSubscribe;
    }
}
