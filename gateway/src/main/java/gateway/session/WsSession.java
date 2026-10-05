package gateway.session;

import java.nio.ByteBuffer;

/**
 * Transport-agnostic WebSocket session.
 *
 * <p>Any underlying transport (Javalin/Jetty, nv-websocket-client,
 * {@code java.net.http.WebSocket}) adapts to this 4-method interface so that
 * {@code WsRpcChannel} stays decoupled from I/O implementations.
 */
public interface WsSession {

    /**
     * Send a UTF-8 text frame (typically a JSON {@code WsMessage}).
     *
     * @param text JSON payload, never null
     */
    void sendText(String text);

    /**
     * Send a binary frame (typically a file chunk with 20-byte header).
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
