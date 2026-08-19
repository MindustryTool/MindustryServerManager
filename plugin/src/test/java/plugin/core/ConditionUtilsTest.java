package plugin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import plugin.annotations.Condition;
import plugin.annotations.ConditionOn;

public class ConditionUtilsTest {

    public static class AlwaysCondition implements Condition {
        @Override
        public boolean check() {
            return true;
        }
    }

    public static class NeverCondition implements Condition {
        @Override
        public boolean check() {
            return false;
        }
    }

    public static class StringArgsCondition implements Condition {
        private final String[] args;

        public StringArgsCondition(String[] args) {
            this.args = args;
        }

        @Override
        public boolean check() {
            return args.length == 2 && "first".equals(args[0]) && "second".equals(args[1]);
        }
    }

    public static class NoStringArgsCondition implements Condition {
        @Override
        public boolean check() {
            return true;
        }
    }

    static class NoCondition {
    }

    @ConditionOn(value = AlwaysCondition.class)
    static class OneCondition {
    }

    @ConditionOn(value = AlwaysCondition.class)
    @ConditionOn(value = NeverCondition.class)
    static class MultipleConditions {
    }

    @ConditionOn(value = AlwaysCondition.class)
    @ConditionOn(value = AlwaysCondition.class)
    static class MultiplePassingConditions {
    }

    @ConditionOn(value = StringArgsCondition.class, args = {"first", "second"})
    static class ArgsInjected {
    }

    @ConditionOn(value = StringArgsCondition.class, args = {"first"})
    static class ArgsMismatched {
    }

    @ConditionOn(value = NoStringArgsCondition.class, args = {"ignored"})
    static class MissingStringArgsConstructor {
    }

    static class Methods {
        public void noCondition() {
        }

        @ConditionOn(value = AlwaysCondition.class)
        public void oneCondition() {
        }

        @ConditionOn(value = AlwaysCondition.class)
        @ConditionOn(value = NeverCondition.class)
        public void multipleConditions() {
        }
    }

    private Method method(String name) throws Exception {
        return Methods.class.getDeclaredMethod(name);
    }

    @Test
    void noConditionIsAlwaysEnabled() {
        assertTrue(ConditionUtils.passes(NoCondition.class));
    }

    @Test
    void singleConditionPasses() {
        assertTrue(ConditionUtils.passes(OneCondition.class));
    }

    @Test
    void singleConditionFails() {
        assertFalse(ConditionUtils.passes(MultipleConditions.class));
    }

    @Test
    void multipleConditionsAreAnded() {
        assertFalse(ConditionUtils.passes(MultipleConditions.class));
    }

    @Test
    void multiplePassingConditionsPass() {
        assertTrue(ConditionUtils.passes(MultiplePassingConditions.class));
    }

    @Test
    void argsAreInjectedIntoStringArrayConstructor() {
        assertTrue(ConditionUtils.passes(ArgsInjected.class));
        assertFalse(ConditionUtils.passes(ArgsMismatched.class));
    }

    @Test
    void missingStringArrayConstructorThrows() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ConditionUtils.passes(MissingStringArgsConstructor.class));
        assertTrue(e.getMessage().contains(MissingStringArgsConstructor.class.getName()));
    }

    @Test
    void methodWithoutConditionIsEnabled() throws Exception {
        assertTrue(ConditionUtils.passes(method("noCondition")));
    }

    @Test
    void methodWithSingleCondition() throws Exception {
        assertTrue(ConditionUtils.passes(method("oneCondition")));
    }

    @Test
    void methodWithMultipleConditionsIsAnded() throws Exception {
        assertFalse(ConditionUtils.passes(method("multipleConditions")));
    }
}
