package server.utils;

import java.io.Serial;

public class ApiError extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -5611371618649386587L;

    public final int status;

    public ApiError(int status, String message) {
        super(message);
        this.status = status;
    }

    public ApiError(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    @Override
    public String getMessage() {
        return String.format("%d: %s", status, super.getMessage());
    }

    @Override
    public String toString() {
        return String.format("%d: %s", status, super.getMessage());
    }

    public static ApiError badRequest(String message) {
        return new ApiError(400, message);
    }

    public static ApiError unauthorized() {
        return new ApiError(401, "You are unauthorized");
    }

    public static ApiError forbidden() {
        return new ApiError(403, "You have no permission to access this");
    }

    public static ApiError forbidden(String message) {
        return new ApiError(403, message);
    }

    public static ApiError conflict(String message) {
        return new ApiError(409, message);
    }

    public static ApiError internal() {
        return new ApiError(500, "Internal Server Error");
    }

    public static ApiError notFound(Object id, String contentType) {
        return new ApiError(404, String.format("Data not found (%s:%s)", contentType, id.toString()));
    }
}
