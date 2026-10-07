package gateway.rpc;

public interface PushHandle {
    /**
     * @param event the event object to send (will be serialized as JSON)
     */
    void push(Object event);

    /**
     * End the subscription cleanly. No frame is sent to the client.
     * Invokes {@link #onClose(Runnable)} callbacks.
     */
    void complete();

    /**
     * End the subscription with an error. Sends an error frame to the client.
     * Invokes {@link #onClose(Runnable)} callbacks.
     *
     * @param reason error reason sent to the client
     */
    void fail(String reason);

    /**
     * @return true if the subscription has been closed (by client unsubscribe,
     *         server fail/complete, or connection loss)
     */
    boolean isClosed();

    /**
     * Register a callback to be invoked when the subscription ends.
     * If already closed, the callback runs immediately.
     *
     * @param callback the cleanup action
     */
    void onClose(Runnable callback);
}
