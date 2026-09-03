package plugin.core;

import java.lang.annotation.Annotation;

@FunctionalInterface
public interface ClassAnnotationHandler<T extends Annotation> {
    void handle(T annotation, Object instance);
}
