package gateway.client;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class WsCauses {

    private WsCauses() {
    }

    static Throwable rootCause(Throwable e) {
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
