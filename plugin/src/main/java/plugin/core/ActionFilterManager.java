package plugin.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

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

        Vars.netServer.admins.addActionFilter(action -> {
            try {
                var parameters = method.getParameters();
                var resolved = new Object[parameters.length];
                for (int i = 0; i < parameters.length; i++) {
                    Parameter parameter = parameters[i];
                    if (parameter.getType() == PlayerAction.class) {
                        resolved[i] = action;
                    } else {
                        resolved[i] = Registry.get(parameter.getType());
                    }
                }

                return (Boolean) method.invoke(instance, resolved);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                Log.err("Error in action filter method " + method, e);
                return false;
            }
        });
    }
}
