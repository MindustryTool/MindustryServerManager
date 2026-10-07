package gateway.rpc;

import java.util.function.BiFunction;

final class StreamHandlerEntry<Meta, Res> {
    final Class<Meta> metaClass;
    final BiFunction<Meta, byte[], Res> fn;

    StreamHandlerEntry(Class<Meta> metaClass, Class<Res> responseType, BiFunction<Meta, byte[], Res> fn) {
        this.metaClass = metaClass;
        this.fn = fn;
    }
}
