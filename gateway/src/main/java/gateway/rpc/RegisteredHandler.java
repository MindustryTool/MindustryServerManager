package gateway.rpc;

import java.util.function.Function;

final class RegisteredHandler<Req, Res> {
    final Class<Req> requestClass;
    final Function<RequestContext<Req>, Res> fn;

    RegisteredHandler(Class<Req> requestClass, Function<RequestContext<Req>, Res> fn) {
        this.requestClass = requestClass;
        this.fn = fn;
    }
}
