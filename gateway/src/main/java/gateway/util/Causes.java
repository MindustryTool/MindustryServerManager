package gateway.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class Causes {

    private Causes() {
    }

    public static Throwable rootCause(Throwable e) {
        Throwable cause = e;

        while (cause instanceof ExecutionException && cause.getCause() != null) {
            cause = cause.getCause();
        }

        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause;
    }
}
