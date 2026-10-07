package gateway.rpc;

public final class WsProtocol {

    private WsProtocol() {
    }

    /** Control type carrying stream metadata ahead of binary chunks. */
    public static final String STREAM_START_TYPE = "stream-start";
    /** Control type closing a stream after its binary chunks. */
    public static final String STREAM_DONE_TYPE = "stream-done";
    /** Control type aborting a live stream (fire-and-forget notification). */
    public static final String STREAM_ABORT_TYPE = "stream-abort";
    /**
     * Control type for subscription requests (reserved, never usable as application
     * type).
     */
    public static final String SUBSCRIBE_TYPE = "subscribe";
    /**
     * Control type for unsubscription requests (reserved, never usable as
     * application type).
     */
    public static final String UNSUBSCRIBE_TYPE = "unsubscribe";
    /** Close code sent to a session replaced by a newer open (private use range). */
    public static final int REPLACED_CLOSE_CODE = 4234;
    /** Largest reassembled stream accepted before failing loud (32 MiB). */
    public static final int MAX_STREAM_BYTES = 32 * 1024 * 1024;

    public static void rejectStreamControlType(String type) {
        if (STREAM_START_TYPE.equals(type) || STREAM_DONE_TYPE.equals(type)
                || STREAM_ABORT_TYPE.equals(type)
                || SUBSCRIBE_TYPE.equals(type)
                || UNSUBSCRIBE_TYPE.equals(type)) {
            throw new IllegalArgumentException("type is reserved for stream/subscription control frames: " + type);
        }
    }
}
