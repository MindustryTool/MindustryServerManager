package gateway.stream;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/**
 * 20-byte binary framing header for chunked file transfers.
 *
 * <pre>
 * 0..15  : UUID transferId (most-significant bits, then least-significant bits, big-endian)
 * 16..19 : int chunkIndex (big-endian)
 * 20..   : raw payload bytes (max 64 KB, see {@link FileChunkStreamer#MAX_CHUNK_BYTES})
 * </pre>
 */
public final class FileTransferHeader {

    public static final int HEADER_SIZE = 20;

    private final UUID transferId;
    private final int chunkIndex;

    public FileTransferHeader(UUID transferId, int chunkIndex) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0");
        }
        this.chunkIndex = chunkIndex;
    }

    public UUID transferId() {
        return transferId;
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    /**
     * Encode this header into a 20-byte big-endian buffer (position 0, limit 20).
     */
    public ByteBuffer encode() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.putLong(transferId.getMostSignificantBits());
        buf.putLong(transferId.getLeastSignificantBits());
        buf.putInt(chunkIndex);
        buf.flip();
        return buf;
    }

    /**
     * Encode header + payload into a single frame ready for {@code WsSession.sendBinary}.
     */
    public ByteBuffer encodeFrame(byte[] payload, int offset, int length) {
        Objects.requireNonNull(payload, "payload");
        if (length > FileChunkStreamer.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("chunk payload exceeds 64KB: " + length);
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + length);
        buf.putLong(transferId.getMostSignificantBits());
        buf.putLong(transferId.getLeastSignificantBits());
        buf.putInt(chunkIndex);
        buf.put(payload, offset, length);
        buf.flip();
        return buf;
    }

    /**
     * Decode the leading 20 bytes of {@code frame} (does not consume payload).
     *
     * @param frame buffer positioned at the start of a chunk frame
     * @return parsed header
     * @throws IllegalArgumentException when fewer than 20 readable bytes remain
     */
    public static FileTransferHeader decode(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.remaining() < HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "Chunk frame too short: need >= 20 bytes, got " + frame.remaining());
        }
        int pos = frame.position();
        long msb = frame.getLong(pos);
        long lsb = frame.getLong(pos + 8);
        int index = frame.getInt(pos + 16);
        if (index < 0) {
            throw new IllegalArgumentException("Negative chunkIndex: " + index);
        }
        return new FileTransferHeader(new UUID(msb, lsb), index);
    }

    /**
     * Extract the payload bytes that follow the 20-byte header.
     * Does not mutate {@code frame}'s position.
     */
    public static byte[] payloadOf(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
        decode(frame); // validates length
        int pos = frame.position();
        int payloadLen = frame.limit() - pos - HEADER_SIZE;
        byte[] out = new byte[payloadLen];
        if (frame.hasArray()) {
            System.arraycopy(frame.array(), frame.arrayOffset() + pos + HEADER_SIZE, out, 0, payloadLen);
        } else {
            ByteBuffer dup = frame.duplicate();
            dup.position(pos + HEADER_SIZE);
            dup.get(out);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileTransferHeader)) return false;
        FileTransferHeader that = (FileTransferHeader) o;
        return chunkIndex == that.chunkIndex && transferId.equals(that.transferId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, chunkIndex);
    }

    @Override
    public String toString() {
        return "FileTransferHeader{transferId=" + transferId + ", chunkIndex=" + chunkIndex + '}';
    }
}
