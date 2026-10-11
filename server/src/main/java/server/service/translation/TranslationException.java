package server.service.translation;

/**
 * Categorized failure from an upstream translation provider.
 * Temporary errors (rate limits, 5xx, timeouts) trigger cooldown backoff.
 * Permanent errors (bad request, unsupported language, malformed payload)
 * are recorded for metrics without entering cooldown.
 */
public class TranslationException extends Exception {

    private static final long serialVersionUID = 1L;

    public TranslationException(String message) {
        super(message);
    }

    public TranslationException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Recoverable failure: rate limit, outage, timeout, connection error. */
    public static final class Temporary extends TranslationException {
        private static final long serialVersionUID = 1L;
        public Temporary(String message) {
            super(message);
        }

        public Temporary(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Client-side failure: bad request, unsupported language, malformed body. */
    public static final class Permanent extends TranslationException {
        private static final long serialVersionUID = 1L;
        public Permanent(String message) {
            super(message);
        }

        public Permanent(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** HTTP status to exception mapping shared by providers. */
    public static TranslationException fromHttpStatus(String providerName, int statusCode, String body) {
        String message = providerName + " failed with HTTP " + statusCode + ": " + body;
        if (statusCode == 429 || (statusCode >= 500 && statusCode <= 599)) {
            return new Temporary(message);
        }
        return new Permanent(message);
    }

    /** I/O failure mapping: timeouts and connection errors are temporary. */
    public static Temporary fromIoError(String providerName, Exception cause) {
        return new Temporary(providerName + " I/O failure: " + cause.getMessage(), cause);
    }
}
