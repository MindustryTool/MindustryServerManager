package plugin.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import plugin.annotations.Condition;
import plugin.annotations.ConditionOn;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryHandlerTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface CustomClassAnno {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface CustomFieldAnno {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface CustomMethodAnno {
        String value();
    }

    public static class AlwaysTrueCondition implements Condition {
        @Override
        public boolean check() {
            return true;
        }
    }

    public static class AlwaysFalseCondition implements Condition {
        @Override
        public boolean check() {
            return false;
        }
    }

    @CustomClassAnno("test-class")
    static class SampleComponent {
        @CustomFieldAnno("test-field")
        public String field;

        @CustomMethodAnno("test-method")
        @ConditionOn(AlwaysTrueCondition.class)
        public void executeAllowed() {
        }

        @CustomMethodAnno("skipped-method")
        @ConditionOn(AlwaysFalseCondition.class)
        public void executeSkipped() {
        }
    }

    @AfterEach
    void tearDown() {
        Registry.destroy();
        Registry.clearHandlers();
    }

    @Test
    void handlersAreInvokedWithConditionFiltering() {
        List<String> invoked = new ArrayList<>();

        Registry.registerClassHandler(CustomClassAnno.class, (anno, instance) -> {
            invoked.add("class:" + anno.value());
        });

        Registry.registerFieldHandler(CustomFieldAnno.class, (anno, field, instance) -> {
            invoked.add("field:" + anno.value());
        });

        Registry.registerMethodHandler(CustomMethodAnno.class, (anno, method, instance) -> {
            invoked.add("method:" + anno.value());
        });

        Registry.init(SampleComponent.class);

        assertTrue(invoked.contains("class:test-class"));
        assertTrue(invoked.contains("field:test-field"));
        assertTrue(invoked.contains("method:test-method"));
        // The method with AlwaysFalseCondition should be skipped
        assertEquals(3, invoked.size());
    }
}
