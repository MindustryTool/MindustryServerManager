package plugin.workflow.errors;

public class WorkflowError extends RuntimeException {
    private static final long serialVersionUID = 930829348901L;

    public WorkflowError(String message) {
        super(message);
    }

    public WorkflowError(String message, Throwable cause) {
        super(message, cause);
    }

    public WorkflowError(Throwable cause) {
        super(cause);
    }

    public WorkflowError(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

}
