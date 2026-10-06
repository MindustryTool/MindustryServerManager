package gateway.session;

import java.nio.ByteBuffer;

public interface WsSession {

    /**
     * Send a UTF-8 text frame (typically a JSON {@code WsMessage}).
     *
     * <p>Call order is delivery order: frames are transmitted in the order
     * {@code sendText}/{@code sendBinary} are invoked, so a stream
     * {@code start} frame is never reordered after its chunks.
     *
     * @param text JSON payload, never null
     */
    void sendText(String text);

    /**
     * Send a binary frame (typically a stream chunk with 20-byte header).
     *
     * <p>Call order is delivery order, see {@link #sendText(String)}.
     *
     * @param data buffer positioned for reading, never null
     */
    void sendBinary(ByteBuffer data);

    /**
     * Close the session.
     *
     * @param code   WebSocket close code (e.g. 1000)
     * @param reason human-readable reason, may be null
     */
    void close(int code, String reason);

    /**
     * @return true while the underlying transport can still send frames
     */
    boolean isOpen();
}
