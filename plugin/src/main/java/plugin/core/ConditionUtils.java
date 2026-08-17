package plugin.core;

import plugin.annotations.Condition;
import plugin.annotations.ConditionOn;

import java.lang.reflect.Method;

public final class ConditionUtils {

    private ConditionUtils() {
    }

    public static boolean passes(Class<?> type) {
        if (!type.isAnnotationPresent(ConditionOn.class)) {
            return true;
        }

        ConditionOn annotation = type.getAnnotation(ConditionOn.class);
        return check(annotation, type.getName());
    }

    public static boolean passes(Method method) {
        if (!method.isAnnotationPresent(ConditionOn.class)) {
            return true;
        }

        ConditionOn annotation = method.getAnnotation(ConditionOn.class);
        return check(annotation, method.getName());
    }

    private static boolean check(ConditionOn annotation, String target) {
        Class<? extends Condition> conditionClass = annotation.value();

        try {
            Condition condition = conditionClass.getDeclaredConstructor().newInstance();
            return condition.check();
        } catch (Exception e) {
            throw new RuntimeException("Failed to check condition for " + target, e);
        }
    }
}