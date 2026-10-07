package gateway.rpc;

public final class WsProtocol {

    private WsProtocol() {
    }

    /** Kind for RPC calls expecting exactly one answer. */
    public static final String REQUEST_TYPE = "request";
    /** Kind for successful RPC answers. */
    public static final String RESPONSE_TYPE = "response";
    /** Kind for failed RPC answers. Payload carries the detail string. */
    public static final String RESPONSE_ERROR_TYPE = "response-error";
    /** Kind for one-way calls that are never answered. */
    public static final String NOTIFICATION_TYPE = "notification";
    /** Kind carrying byte-stream metadata ahead of binary chunks. */
    public static final String STREAM_START_TYPE = "stream-start";
    /** Kind closing a byte stream after its binary chunks. */
    public static final String STREAM_DONE_TYPE = "stream-done";
    /** Kind aborting a live byte stream (fire-and-forget notification). */
    public static final String STREAM_ABORT_TYPE = "stream-abort";
    /** Kind starting a reply-with-stream that answers a request. */
    public static final String STREAM_REPLY_START_TYPE = "stream-reply-start";
    /** Kind completing a reply-with-stream that answers a request. */
    public static final String STREAM_REPLY_DONE_TYPE = "stream-reply-done";
    /** Kind opening an event stream (SSE-like). Subject is the event name. */
    public static final String LISTEN_TYPE = "listen";
    /** Kind acknowledging an opened event stream. */
    public static final String LISTENING_TYPE = "listening";
    /** Kind for pushed event-stream events. */
    public static final String EVENT_TYPE = "event";
    /** Kind stopping an event stream. Answered with listen-ended. */
    public static final String UNLISTEN_TYPE = "unlisten";
    /** Kind marking an event stream gracefully ended. */
    public static final String LISTEN_ENDED_TYPE = "listen-ended";
    /** Kind marking an event stream failed. Payload carries the detail. */
    public static final String LISTEN_ERROR_TYPE = "listen-error";
    /** Close code sent to a session replaced by a newer open (private use range). */
    public static final int REPLACED_CLOSE_CODE = 4234;
    /** Largest reassembled stream accepted before failing loud (32 MiB). */
    public static final int MAX_STREAM_BYTES = 32 * 1024 * 1024;
}
