package plugin.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import arc.util.Log;
import mindustry.Vars;
import mindustry.net.Administration.PlayerAction;
import plugin.annotations.PlayerActionFilter;

public class ActionFilterManager {
    public void addFilter(PlayerActionFilter filter, Method method, Object instance) {
        var resultType = method.getReturnType();

        if (resultType != Boolean.class) {
            Log.err("Action filter method @ must return boolean", method);
            return;
        }

        var parameterTypes = method.getParameterTypes();
        var template = new Object[parameterTypes.length];
        int actionIndex = -1;

        for (int i = 0; i < parameterTypes.length; i++) {
            var type = parameterTypes[i];

            if (type == PlayerAction.class) {
                if (actionIndex != -1) {
                    Log.err("Action filter method @ must accept exactly one PlayerAction", method);
                    return;
                }

                actionIndex = i;
                continue;
            }

            template[i] = Registry.get(type);
        }

        if (actionIndex == -1) {
            Log.err("Action filter method @ must accept a PlayerAction", method);
            return;
        }

        var index = actionIndex;

        Vars.netServer.admins.addActionFilter(action -> {
            var args = template.clone();
            args[index] = action;

            try {
                return (Boolean) method.invoke(instance, args);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                Log.err("Error in action filter method " + method, e);
                return false;
            }
        });
    }
}
