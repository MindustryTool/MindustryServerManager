package gateway.wire;

import java.util.Objects;

/**
 * Return value for request handlers that answer with a stream instead of a
 * single frame. The requester's future resolves with {@code data}.
 */
public record StreamReply(byte[] data, Object metadata) {
    public StreamReply {
        Objects.requireNonNull(data, "data");
    }

    public StreamReply(byte[] data) {
        this(data, null);
    }
}
