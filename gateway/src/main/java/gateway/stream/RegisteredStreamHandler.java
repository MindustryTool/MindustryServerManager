package gateway.stream;

import java.util.function.BiFunction;

final class RegisteredStreamHandler<Meta, Res> {
    final Class<Meta> metaClass;
    final BiFunction<Meta, byte[], Res> fn;

    RegisteredStreamHandler(Class<Meta> metaClass, Class<Res> responseType, BiFunction<Meta, byte[], Res> fn) {
        this.metaClass = metaClass;
        this.fn = fn;
    }
}
