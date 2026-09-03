package plugin.orm;

public class OrmException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OrmException(String message) {
        super(message);
    }

    public OrmException(String message, Throwable cause) {
        super(message, cause);
    }

    public OrmException(Throwable cause) {
        super(cause);
    }
}
