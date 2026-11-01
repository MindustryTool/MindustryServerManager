package plugin.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class ComponentRegistryConstructorTest {

    @Test
    void allComponentsHaveSingleOrZeroArgConstructor() {
        for (Class<?> componentClass : ComponentRegistry.COMPONENTS) {
            Constructor<?>[] constructors = componentClass.getDeclaredConstructors();
            boolean valid = constructors.length == 1 || Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
            assertTrue(valid, "Component " + componentClass.getName() + " has multiple constructors but no 0-arg constructor");
        }
    }
}
