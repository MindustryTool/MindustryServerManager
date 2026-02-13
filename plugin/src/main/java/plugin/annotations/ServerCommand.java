package plugin.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Component
public @interface ServerCommand {

    String name();

    String description();
}
