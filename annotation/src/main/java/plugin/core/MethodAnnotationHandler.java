package plugin.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

@FunctionalInterface
public interface MethodAnnotationHandler<T extends Annotation> {
    void handle(T annotation, Method method, Object instance);
}
