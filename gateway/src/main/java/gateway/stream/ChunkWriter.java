package gateway.stream;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Splits raw bytes into {@code <= 64 KB} binary frames prefixed with a
 * 20-byte {@link ChunkHeader} (UUID transferId + chunkIndex).
 *
 * <p>Small 64 KB frames interleave fairly with control / heartbeat frames so
 * one large file cannot starve the socket.
 */
public final class ChunkWriter {

    /** Maximum payload bytes per chunk frame (64 KiB). */
    public static final int MAX_CHUNK_BYTES = 64 * 1024;

    private ChunkWriter() {
    }

    /**
     * Chunk {@code data} into frames for {@code transferId}.
     *
     * @return frames in ascending chunk order, each ready to send
     *         (position 0, limit = 20 + payload)
     */
    public static List<ByteBuffer> chunk(UUID transferId, byte[] data) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(data, "data");
        List<ByteBuffer> frames = new ArrayList<>((data.length / MAX_CHUNK_BYTES) + 1);
        int index = 0;
        for (int offset = 0; offset < data.length || (data.length == 0 && index == 0); offset += MAX_CHUNK_BYTES) {
            int len = Math.min(MAX_CHUNK_BYTES, data.length - offset);
            if (len < 0) {
                len = 0;
            }
            frames.add(new ChunkHeader(transferId, index++).encodeFrame(data, offset, len));
            if (data.length == 0) {
                break;
            }
        }
        return frames;
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
