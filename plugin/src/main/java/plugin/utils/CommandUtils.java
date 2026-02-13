package plugin.utils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import mindustry.gen.Player;
import plugin.annotations.Param;
import plugin.commands.ParamException;
import plugin.core.Registry;
import plugin.type.Session;

public class CommandUtils {
    public static Object[] mapParams(Method method, String[] args, Session session) throws ParamException {
        var parameters = method.getParameters();
        var resolved = new Object[parameters.length];
        int argIndex = 0;

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];

            if (param.getType() == Session.class) {
                if (session == null) {
                    throw new ParamException("Session is null");
                }

                resolved[i] = session;
                continue;
            }

            if (param.getType() == Player.class) {
                if (session == null) {
                    throw new ParamException("Session is null");
                }

                resolved[i] = session.player;
                continue;
            }

            if (!param.isAnnotationPresent(Param.class)) {
                var dep = Registry.get(param.getType());
                if (dep == null) {
                    throw new ParamException("Dependency not found: " + param.getType().getSimpleName());
                }
                resolved[i] = dep;
                continue;
            }

            var meta = param.getAnnotation(Param.class);

            if (meta.variadic()) {
                var values = new String[args.length - argIndex];
                System.arraycopy(args, argIndex, values, 0, values.length);
                resolved[i] = values;
                argIndex = args.length;
                continue;
            }

            if (argIndex >= args.length && meta.required()) {
                throw new ParamException("Missing argument: " + param.getName());
            }

            if (argIndex > (args.length - 1) && meta.required() == false) {
                resolved[i] = null;
                continue;
            }

            String value = args[argIndex++];

            resolved[i] = convert(value, param.getType());
        }

        return resolved;
    }

    private static Object convert(String value, Class<?> type) {

        if (type == String.class)
            return value;
        if (type == int.class || type == Integer.class)
            return Integer.parseInt(value);
        if (type == long.class || type == Long.class)
            return Long.parseLong(value);
        if (type == double.class || type == Double.class)
            return Double.parseDouble(value);
        if (type == float.class || type == Float.class)
            return Float.parseFloat(value);
        if (type == boolean.class || type == Boolean.class)
            return Boolean.parseBoolean(value);

        throw new ParamException("Unsupported param type: " + type.getSimpleName());
    }

    public static String toParamText(Method method) {

        StringBuilder paramText = new StringBuilder("");
        var hasVariadic = false;
        var hasOptional = false;

        for (Parameter methodParam : method.getParameters()) {
            if (methodParam.isAnnotationPresent(Param.class)) {
                Param param = methodParam.getAnnotation(Param.class);
                if (hasVariadic) {
                    throw new IllegalArgumentException("Variadic argument must be the last argument and only one");
                }

                if (param.variadic()) {
                    hasVariadic = true;
                }

                if (param.required() && hasOptional) {
                    throw new IllegalArgumentException("Optional argument must be the last argument");
                }

                if (param.required() == false) {
                    hasOptional = true;
                }

                if (param.required()) {
                    paramText.append("<" + param.name() + (param.variadic() ? "..." : "") + ">");
                } else if (param.required() == false) {
                    paramText.append("[" + param.name() + (param.variadic() ? "..." : "") + "]");
                }

                paramText.append(" ");
            }
        }

        return paramText.toString();
    }
}
