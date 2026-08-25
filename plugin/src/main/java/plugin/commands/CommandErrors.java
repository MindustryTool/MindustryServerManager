package plugin.commands;

import java.lang.reflect.InvocationTargetException;

public class CommandErrors {

    private CommandErrors() {
    }

    public static Throwable unwrap(Throwable throwable) {
        var current = throwable;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static String describe(Throwable throwable) {
        var message = throwable.getMessage();
        return message != null ? message : throwable.getClass().getSimpleName();
    }
}
