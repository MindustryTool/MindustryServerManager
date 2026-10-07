package gateway.rpc;

public interface PushHandle {
    /**
     * @param event the event object to send (will be serialized as JSON)
     */
    void push(Object event);

    /**
     * End the event stream cleanly. Sends a {@code listen-ended} frame.
     * Invokes {@link #onClose(Runnable)} callbacks.
     */
    void complete();

    /**
     * End the event stream with an error. Sends a {@code listen-error} frame.
     * Invokes {@link #onClose(Runnable)} callbacks.
     *
     * @param reason error reason sent to the client
     */
    void fail(String reason);

    /**
     * @return true if the event stream has been closed (by client unlisten,
     *         server fail/complete, or connection loss)
     */
    boolean isClosed();

    /**
     * Register a callback to be invoked when the event stream ends.
     * If already closed, the callback runs immediately.
     *
     * @param callback the cleanup action
     */
    void onClose(Runnable callback);
}
