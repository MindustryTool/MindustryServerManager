package plugin.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Component
public @interface ClientCommand {

    String name();

    String description();

    boolean admin() default true;
}
