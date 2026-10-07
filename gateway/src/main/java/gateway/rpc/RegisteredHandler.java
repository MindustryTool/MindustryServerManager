package gateway.rpc;

import java.util.function.Function;

final class HandlerEntry<Req, Res> {
    final Class<Req> requestClass;
    final Function<Req, Res> fn;

    HandlerEntry(Class<Req> requestClass, Function<Req, Res> fn) {
        this.requestClass = requestClass;
        this.fn = fn;
    }
}
