package gateway.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class SubscriptionHandlerEntry {
    final Class<?> paramsClass;
    final Function<SubscriptionRequest<Object>, CompletableFuture<Void>> onSubscribe;

    SubscriptionHandlerEntry(Class<?> paramsClass,
            Function<SubscriptionRequest<Object>, CompletableFuture<Void>> onSubscribe) {
        this.paramsClass = paramsClass;
        this.onSubscribe = onSubscribe;
    }
}
