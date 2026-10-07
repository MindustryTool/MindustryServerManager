package gateway.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * 20-byte {@link FileTransferHeader} (UUID transferId + chunkIndex).
 *
 * <p>Small 64 KB frames interleave fairly with control / heartbeat frames so
 * one large file cannot starve the socket.
 */
public final class FileChunkStreamer {

    /** Maximum payload bytes per chunk frame (64 KiB). */
    public static final int MAX_CHUNK_BYTES = 64 * 1024;

    private FileChunkStreamer() {
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
            frames.add(new FileTransferHeader(transferId, index++).encodeFrame(data, offset, len));
            if (data.length == 0) {
                break;
            }
        }
        return frames;
    }

    /**
     * Chunk all bytes read from {@code in} (fully consumed, not closed).
     */
    public static List<ByteBuffer> chunk(UUID transferId, InputStream in) throws IOException {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(in, "in");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return chunk(transferId, out.toByteArray());
    }

    /**
     * Split with a caller-provided transfer id generated fresh.
     *
     * @return a {@link ChunkedFile} holding the id, frames, size and SHA-256
     */
    public static ChunkedFile chunkWithNewId(byte[] data) {
        UUID id = UUID.randomUUID();
        List<ByteBuffer> frames = chunk(id, data);
        return new ChunkedFile(id, frames, data.length, sha256Hex(data));
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

    /** Value object bundling frames with integrity metadata. */
    public record ChunkedFile(UUID transferId, List<ByteBuffer> frames, long totalBytes, String sha256Hex) {
    }
}
