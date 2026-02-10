package plugin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Schedule {
    long delay() default -1;
    long fixedDelay() default -1;
    long fixedRate() default -1;
    TimeUnit unit() default TimeUnit.MILLISECONDS;
}
