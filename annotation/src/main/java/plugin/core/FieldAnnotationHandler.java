package plugin.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;

@FunctionalInterface
public interface FieldAnnotationHandler<T extends Annotation> {
    void handle(T annotation, Field field, Object instance);
}
