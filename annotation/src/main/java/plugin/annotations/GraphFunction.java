package plugin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GraphFunction {
    String id();

    String category() default "";

    String description() default "";

    String threadReq() default "MAIN_THREAD";

    String[] aliases() default {};

    boolean advanced() default false;
}
