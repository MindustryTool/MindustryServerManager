package gateway.stream;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStreamingTest {

    @Test
    void headerEncodeDecodeRoundTrip() {
        UUID id = UUID.randomUUID();
        ChunkHeader header = new ChunkHeader(id, 7);
        ByteBuffer encoded = header.encode();
        assertEquals(20, encoded.remaining());
        ChunkHeader decoded = ChunkHeader.decode(encoded);
        assertEquals(header, decoded);
        assertEquals(id, decoded.transferId());
        assertEquals(7, decoded.chunkIndex());
    }

    @Test
    void chunkAndReassembleRoundTrip() {
        byte[] data = new byte[150 * 1024];
        new Random(42).nextBytes(data);
        UUID id = UUID.randomUUID();

        List<ByteBuffer> frames = ChunkWriter.chunk(id, data);
        assertEquals(3, frames.size()); // 64k + 64k + 22k
        for (ByteBuffer f : frames) {
            assertTrue(f.remaining() <= 20 + ChunkWriter.MAX_CHUNK_BYTES);
        }

        ChunkAssembler receiver = new ChunkAssembler();
        for (ByteBuffer f : frames) {
            receiver.receive(f.duplicate());
        }
        String sha = ChunkWriter.sha256Hex(data);
        byte[] out = receiver.assemble(id, sha);
        assertArrayEquals(data, out);
    }

    @Test
    void checksumMismatchAborts() {
        byte[] data = "hello gateway streaming".getBytes();
        UUID id = UUID.randomUUID();
        List<ByteBuffer> frames = ChunkWriter.chunk(id, data);

        ChunkAssembler receiver = new ChunkAssembler();
        frames.forEach(f -> receiver.receive(f.duplicate()));

        assertThrows(SecurityException.class, () -> receiver.assemble(id, "00".repeat(32)));
        assertFalse(receiver.hasTransfer(id));
    }

    @Test
    void missingChunkDetected() {
        byte[] data = new byte[130 * 1024];
        new Random(7).nextBytes(data);
        UUID id = UUID.randomUUID();
        List<ByteBuffer> frames = ChunkWriter.chunk(id, data);

        ChunkAssembler receiver = new ChunkAssembler();
        receiver.receive(frames.get(0).duplicate());
        receiver.receive(frames.get(2).duplicate()); // skip index 1

        assertThrows(IllegalStateException.class,
                () -> receiver.assemble(id, ChunkWriter.sha256Hex(data)));
    }
}
