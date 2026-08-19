package plugin.core;

import plugin.annotations.Condition;
import plugin.annotations.ConditionOn;

import java.lang.reflect.Method;

public final class ConditionUtils {

    private ConditionUtils() {
    }

    public static boolean passes(Class<?> type) {
        return passes(type.getAnnotationsByType(ConditionOn.class), type.getName());
    }

    public static boolean passes(Method method) {
        return passes(method.getAnnotationsByType(ConditionOn.class), method.getName());
    }

    private static boolean passes(ConditionOn[] annotations, String target) {
        for (ConditionOn annotation : annotations) {
            if (!check(annotation, target)) {
                return false;
            }
        }
        return true;
    }

    private static boolean check(ConditionOn annotation, String target) {
        Class<? extends Condition> conditionClass = annotation.value();

        try {
            Condition condition;
            if (annotation.args().length > 0) {
                condition = conditionClass.getDeclaredConstructor(String[].class).newInstance((Object) annotation.args());
            } else {
                condition = conditionClass.getDeclaredConstructor().newInstance();
            }
            return condition.check();
        } catch (Exception e) {
            throw new RuntimeException("Failed to check condition for " + target, e);
        }
    }
}