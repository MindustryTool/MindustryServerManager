package plugin.orm;

public class OrmException extends RuntimeException {

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
