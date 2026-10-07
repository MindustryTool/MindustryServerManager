package gateway.stream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reassembles binary chunk frames produced by {@link FileChunkStreamer}.
 *
 * <p>Thread-safe. Callers feed frames via {@link #receive(ByteBuffer)} then
 * finalize with {@link #assemble(UUID, String)} which verifies SHA-256 before
 * returning the concatenated bytes.
 */
public class FileChunkReceiver {

    private final Map<UUID, TreeMap<Integer, byte[]>> buffers = new ConcurrentHashMap<>();

    /**
     * Buffer one binary frame (20-byte header + payload).
     *
     * @param frame buffer positioned at frame start; position is not mutated
     */
    public void receive(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
        FileTransferHeader header = FileTransferHeader.decode(frame);
        byte[] payload = FileTransferHeader.payloadOf(frame);
        if (payload.length > FileChunkStreamer.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Chunk payload exceeds 64KB: " + payload.length);
        }
        buffers.computeIfAbsent(header.transferId(), id -> new TreeMap<>())
                .put(header.chunkIndex(), payload);
    }

    public boolean hasTransfer(UUID transferId) {
        return buffers.containsKey(transferId);
    }

    /** Discard all buffered chunks for a transfer. */
    public void abort(UUID transferId) {
        buffers.remove(transferId);
    }

    /** Discard every pending transfer. */
    public void clear() {
        buffers.clear();
    }

    /**
     * Assemble buffered chunks in index order and verify SHA-256.
     *
     * @param transferId        id parsed from frame headers
     * @param expectedSha256Hex lowercase or uppercase hex, must match computed digest
     * @return concatenated file bytes
     * @throws IllegalStateException when no chunks buffered or an index is missing
     * @throws SecurityException     when the computed SHA-256 differs (buffers discarded)
     */
    public byte[] assemble(UUID transferId, String expectedSha256Hex) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(expectedSha256Hex, "expectedSha256Hex");
        TreeMap<Integer, byte[]> chunks = buffers.get(transferId);
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException("No chunks buffered for transfer: " + transferId);
        }
        List<Integer> missing = new ArrayList<>();
        int max = chunks.lastKey();
        for (int i = 0; i <= max; i++) {
            if (!chunks.containsKey(i)) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing chunks " + missing + " for transfer: " + transferId);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : chunks.values()) {
            out.write(part, 0, part.length);
        }
        byte[] assembled = out.toByteArray();

        String actual = FileChunkStreamer.sha256Hex(assembled);
        if (!actual.equalsIgnoreCase(expectedSha256Hex)) {
            buffers.remove(transferId);
            throw new SecurityException(
                    "SHA-256 mismatch for transfer " + transferId
                            + ": expected=" + expectedSha256Hex + " actual=" + actual);
        }
        buffers.remove(transferId);
        return assembled;
    }

    /**
     * Assemble with an expected chunk count guard (detects truncated streams
     * even when indexes happen to be contiguous from a non-zero start).
     */
    public byte[] assemble(UUID transferId, int expectedChunks, String expectedSha256Hex) {
        TreeMap<Integer, byte[]> chunks = buffers.get(transferId);
        int have = chunks == null ? 0 : chunks.size();
        if (have != expectedChunks) {
            throw new IllegalStateException(
                    "Expected " + expectedChunks + " chunks but buffered " + have + " for " + transferId);
        }
        return assemble(transferId, expectedSha256Hex);
    }
}
