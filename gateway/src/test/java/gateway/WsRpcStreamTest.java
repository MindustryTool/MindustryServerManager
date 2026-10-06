package gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gateway.rpc.WsRpcChannel;
import gateway.session.WsSession;
import gateway.stream.FileChunkStreamer;
import gateway.stream.FileTransferHeader;

class WsRpcStreamTest {

    record DocMeta(String name) {
    }

    static class Loopback implements WsSession {
        WsRpcChannel peer;
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean open = true;
        volatile boolean corruptNextBinary = false;
        volatile int dropChunkIndex = -1;
        volatile boolean dropDone = false;

        @Override
        public void sendText(String text) {
            sent.add(text);
            if (dropDone && text.contains("\"type\":\"" + WsRpcChannel.STREAM_DONE_TYPE + "\"")) {
                return;
            }
            WsRpcChannel p = peer;
            if (p != null) {
                p.onTextMessage(text);
            }
        }

        @Override
        public void sendBinary(ByteBuffer data) {
            ByteBuffer dup = data.duplicate();
            byte[] bytes = new byte[dup.remaining()];
            dup.get(bytes);
            int index = FileTransferHeader.decode(ByteBuffer.wrap(bytes)).chunkIndex();
            if (index == dropChunkIndex) {
                return;
            }
            if (corruptNextBinary) {
                corruptNextBinary = false;
                bytes[FileTransferHeader.HEADER_SIZE] ^= 0xFF;
            }
            WsRpcChannel p = peer;
            if (p != null) {
                p.onBinaryMessage(ByteBuffer.wrap(bytes));
            }
        }

        @Override
        public void close(int code, String reason) {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    private static WsRpcChannel[] pair(Loopback sa, Loopback sb) {
        WsRpcChannel a = WsRpcChannel.create();
        WsRpcChannel b = WsRpcChannel.create();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);
        return new WsRpcChannel[] { a, b };
    }

    @Test
    void byteBufferRoundTripWithTypedAck() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("doc", DocMeta.class, String.class,
                (meta, bytes) -> meta.name() + ":" + new String(bytes, StandardCharsets.UTF_8));

        byte[] payload = "hello-stream".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<String> ack = a.sendStream("doc", new DocMeta("n1"), ByteBuffer.wrap(payload),
                String.class, Duration.ofSeconds(5));
        assertEquals("n1:hello-stream", ack.get(5, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void byteArrayAndInputStreamSendersIncludingEmpty() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("blob", String.class, Integer.class, (meta, bytes) -> bytes.length);

        byte[] payload = new byte[3000];
        Arrays.fill(payload, (byte) 7);
        assertEquals(3000,
                a.sendStream("blob", "m", payload, Integer.class, Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
        assertEquals(3000, a.sendStream("blob", "m", new ByteArrayInputStream(payload), Integer.class,
                Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
        assertEquals(0, a.sendStream("blob", "m", new byte[0], Integer.class, Duration.ofSeconds(5))
                .get(5, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void unknownStreamTypeFailsAck() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        CompletableFuture<String> ack = a.sendStream("ghost-stream", "m", new byte[] { 1 }, String.class,
                Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> ack.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null && err.getCause().getMessage().contains("unknown stream type"));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void checksumMismatchFailsAckWithoutHandler() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.corruptNextBinary = true;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        final boolean[] ran = { false };
        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> {
            ran[0] = true;
            return "ok";
        });

        byte[] payload = new byte[5000];
        Arrays.fill(payload, (byte) 3);
        CompletableFuture<String> ack = a.sendStream("doc", "m", payload, String.class, Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> ack.get(5, TimeUnit.SECONDS));
        assertNotNull(err);
        assertFalse(ran[0], "handler must not run on checksum mismatch");

        a.shutdown();
        b.shutdown();
    }

    @Test
    void missingChunkFailsAck() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.dropChunkIndex = 0;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");

        CompletableFuture<String> ack = a.sendStream("doc", "m", new byte[] { 1, 2, 3 }, String.class,
                Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> ack.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null && err.getCause().getMessage().contains("Incomplete stream"));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void oversizeStreamRejectedByCap() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("big", String.class, String.class, (meta, bytes) -> "ok");

        byte[] payload = new byte[WsRpcChannel.MAX_STREAM_BYTES + 1];
        Arrays.fill(payload, (byte) 9);
        CompletableFuture<String> ack = a.sendStream("big", "m", payload, String.class, Duration.ofSeconds(15));
        Exception err = assertThrows(Exception.class, () -> ack.get(15, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null && err.getCause().getMessage().contains("max bytes"));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void unknownBinaryChunkDropped() {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        UUID unknown = UUID.randomUUID();
        ByteBuffer frame = new FileTransferHeader(unknown, 0).encodeFrame(new byte[] { 1 }, 0, 1);
        assertDoesNotThrow(() -> b.onBinaryMessage(frame));
        assertEquals(0, b.pendingStreamCount());

        b.shutdown();
    }

    @Test
    void streamTimeoutFailsAck() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.dropDone = true;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("slow", String.class, String.class, (meta, bytes) -> "ok");

        CompletableFuture<String> ack = a.sendStream("slow", "m", new byte[] { 1 }, String.class,
                Duration.ofMillis(150));
        Exception err = assertThrows(Exception.class, () -> ack.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() instanceof TimeoutException
                || (err.getCause() != null && err.getCause().getCause() instanceof TimeoutException),
                "expected TimeoutException, got: " + err);

        a.shutdown();
        b.shutdown();
    }

    @Test
    void abortDiscardsSlot() throws Exception {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);
        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");

        UUID streamId = UUID.randomUUID();
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        WsRpcChannel.StreamStart start = new WsRpcChannel.StreamStart(streamId, "doc",
                b.getObjectMapper().valueToTree("m"), 1, FileChunkStreamer.sha256Hex(payload));
        String startJson = new ObjectMapper().writeValueAsString(
                WsMessage.<WsRpcChannel.StreamStart>create(WsRpcChannel.STREAM_START_TYPE).withPayload(start));
        b.onTextMessage(startJson);
        assertEquals(1, b.pendingStreamCount());

        assertTrue(b.abortStream(streamId));
        assertEquals(0, b.pendingStreamCount());
        assertFalse(b.abortStream(streamId));

        b.shutdown();
    }

    @Test
    void closeDuringSessionWaitFailsStream() {
        WsRpcChannel a = WsRpcChannel.create();

        CompletableFuture<String> ack = a.sendStream("doc", "m", new byte[] { 1 }, String.class,
                Duration.ofMinutes(1));
        assertFalse(ack.isDone());
        a.onClose(new RuntimeException("boom"));
        assertTrue(ack.isCompletedExceptionally());

        a.shutdown();
    }

    @Test
    void closeClearsReceiverSlot() throws Exception {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);
        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");

        UUID streamId = UUID.randomUUID();
        WsRpcChannel.StreamStart start = new WsRpcChannel.StreamStart(streamId, "doc",
                b.getObjectMapper().valueToTree("m"), 1, FileChunkStreamer.sha256Hex(new byte[] { 1 }));
        String startJson = new ObjectMapper().writeValueAsString(
                WsMessage.<WsRpcChannel.StreamStart>create(WsRpcChannel.STREAM_START_TYPE).withPayload(start));
        b.onTextMessage(startJson);
        assertEquals(1, b.pendingStreamCount());

        b.onClose(new RuntimeException("gone"));
        assertEquals(0, b.pendingStreamCount());

        b.shutdown();
    }

    @Test
    void interleavedStreamsStayIsolated() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("first", String.class, String.class, (meta, bytes) -> "1:" + bytes.length);
        b.registerStreamHandler("second", String.class, String.class, (meta, bytes) -> "2:" + bytes.length);

        byte[] one = new byte[100000];
        byte[] two = new byte[200000];
        Arrays.fill(one, (byte) 1);
        Arrays.fill(two, (byte) 2);
        CompletableFuture<String> ack1 = a.sendStream("first", "m", one, String.class, Duration.ofSeconds(10));
        CompletableFuture<String> ack2 = a.sendStream("second", "m", two, String.class, Duration.ofSeconds(10));
        assertEquals("1:100000", ack1.get(10, TimeUnit.SECONDS));
        assertEquals("2:200000", ack2.get(10, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void replyStreamResolvesRequestWithBytes() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        byte[] payload = new byte[90000];
        Arrays.fill(payload, (byte) 11);
        b.registerHandler("get-file", String.class,
                name -> new WsRpcChannel.StreamReply(payload, Map.of("fileName", name)));

        CompletableFuture<byte[]> res = a.sendRequest("get-file", "x.msav", byte[].class,
                Duration.ofSeconds(10));
        assertArrayEquals(payload, res.get(10, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void replyStreamHandlerThrowFailsRequest() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerHandler("get-file", String.class, name -> {
            throw new IllegalStateException("kaput-reply");
        });

        CompletableFuture<byte[]> res = a.sendRequest("get-file", "x", byte[].class, Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null && err.getCause().getMessage().contains("kaput-reply"));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void replyStreamChecksumMismatchFailsRequest() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sb.corruptNextBinary = true;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        byte[] payload = new byte[5000];
        Arrays.fill(payload, (byte) 4);
        b.registerHandler("get-file", String.class, name -> new WsRpcChannel.StreamReply(payload, null));

        CompletableFuture<byte[]> res = a.sendRequest("get-file", "x", byte[].class, Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null && err.getCause().getMessage().contains("Checksum mismatch"));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void replyStartForUnknownRequestDropped() throws Exception {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sbr = new Loopback();
        b.onOpen(sbr);

        UUID streamId = UUID.randomUUID();
        byte[] payload = new byte[] { 1 };
        WsRpcChannel.StreamStart start = new WsRpcChannel.StreamStart(streamId, "get-file",
                b.getObjectMapper().valueToTree("m"), 1, FileChunkStreamer.sha256Hex(payload));
        WsMessage<WsRpcChannel.StreamStart> msg = WsMessage
                .<WsRpcChannel.StreamStart>create(WsRpcChannel.STREAM_START_TYPE).withPayload(start);
        msg.setResponseOf(UUID.randomUUID());
        b.onTextMessage(new ObjectMapper().writeValueAsString(msg));

        assertEquals(0, b.pendingStreamCount());
        b.shutdown();
    }

    @Test
    void streamControlTypesRejected() {
        WsRpcChannel a = WsRpcChannel.create();
        assertThrows(IllegalArgumentException.class,
                () -> a.registerHandler(WsRpcChannel.STREAM_START_TYPE, String.class, s -> null));
        assertThrows(IllegalArgumentException.class,
                () -> a.registerStreamHandler(WsRpcChannel.STREAM_DONE_TYPE, String.class, String.class,
                        (meta, bytes) -> "x"));
        assertThrows(NullPointerException.class, () -> a.abortStream(null));
        a.shutdown();
    }

    private static JsonNode firstSentOfType(List<String> sent, String type) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String text : sent) {
            if (text.contains("\"type\":\"" + type + "\"")) {
                return mapper.readTree(text);
            }
        }
        return null;
    }

    private static void backdateSlotReserve(WsRpcChannel channel, long ageNanos) throws Exception {
        Field slotsField = WsRpcChannel.class.getDeclaredField("streamSlots");
        slotsField.setAccessible(true);
        Map<?, ?> slots = (Map<?, ?>) slotsField.get(channel);
        assertEquals(1, slots.size());
        Object slot = slots.values().iterator().next();
        Field reservedField = slot.getClass().getDeclaredField("reservedAtNanos");
        reservedField.setAccessible(true);
        reservedField.setLong(slot, System.nanoTime() - ageNanos);
    }

    @Test
    void abortControlTypeRejected() {
        WsRpcChannel a = WsRpcChannel.create();
        assertThrows(IllegalArgumentException.class,
                () -> a.registerHandler(WsRpcChannel.STREAM_ABORT_TYPE, String.class, s -> null));
        assertThrows(IllegalArgumentException.class,
                () -> a.registerStreamHandler(WsRpcChannel.STREAM_ABORT_TYPE, String.class, String.class,
                        (meta, bytes) -> "x"));
        a.shutdown();
    }

    @Test
    void inboundAbortDiscardsSlotWithNoReply() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.dropDone = true;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");
        byte[] payload = "abort-me".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<String> ack = a.sendStream("doc", "m", payload, String.class,
                Duration.ofSeconds(10));
        assertEquals(1, b.pendingStreamCount());
        UUID streamId = UUID.fromString(firstSentOfType(sa.sent,
                WsRpcChannel.STREAM_START_TYPE).get("payload").get("streamId").asText());

        String abortJson = new ObjectMapper().writeValueAsString(
                WsMessage.<WsRpcChannel.StreamAbort>create(WsRpcChannel.STREAM_ABORT_TYPE)
                        .withPayload(new WsRpcChannel.StreamAbort(streamId, "stop")));
        b.onTextMessage(abortJson);

        assertEquals(0, b.pendingStreamCount());
        assertTrue(sb.sent.isEmpty(), "abort must never be answered, got: " + sb.sent);
        assertFalse(ack.isDone(), "sender waiter is untouched by the receiver-side abort");

        a.shutdown();
        b.shutdown();
    }

    @Test
    void unknownAbortDroppedWithNoReply() throws Exception {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);
        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");

        String abortJson = new ObjectMapper().writeValueAsString(
                WsMessage.<WsRpcChannel.StreamAbort>create(WsRpcChannel.STREAM_ABORT_TYPE)
                        .withPayload(new WsRpcChannel.StreamAbort(UUID.randomUUID(), "stop")));
        assertDoesNotThrow(() -> b.onTextMessage(abortJson));

        assertEquals(0, b.pendingStreamCount());
        assertTrue(sb.sent.isEmpty(), "abort for unknown stream must stay silent, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void abortStreamAutoNotifiesPeer() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.dropDone = true;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");
        byte[] payload = "notify-me".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<String> ack = a.sendStream("doc", "m", payload, String.class,
                Duration.ofSeconds(10));
        assertEquals(1, b.pendingStreamCount());
        UUID streamId = UUID.fromString(firstSentOfType(sa.sent,
                WsRpcChannel.STREAM_START_TYPE).get("payload").get("streamId").asText());

        assertTrue(a.abortStream(streamId));
        Exception err = assertThrows(Exception.class, () -> ack.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null && err.getCause().getMessage().contains("aborted"));

        assertEquals(0, b.pendingStreamCount(), "peer discards its slot on the abort notify");
        JsonNode abort = firstSentOfType(sa.sent, WsRpcChannel.STREAM_ABORT_TYPE);
        assertNotNull(abort, "abort notify must be emitted, sent: " + sa.sent);
        assertEquals(streamId.toString(), abort.get("payload").get("streamId").asText());

        a.shutdown();
        b.shutdown();
    }

    @Test
    void doubleAbortEmitsOnce() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.dropDone = true;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");
        CompletableFuture<String> ack = a.sendStream("doc", "m", new byte[] { 1 }, String.class,
                Duration.ofSeconds(10));
        UUID streamId = UUID.fromString(firstSentOfType(sa.sent,
                WsRpcChannel.STREAM_START_TYPE).get("payload").get("streamId").asText());

        assertTrue(a.abortStream(streamId));
        assertFalse(a.abortStream(streamId));
        long aborts = sa.sent.stream()
                .filter(t -> t.contains("\"type\":\"" + WsRpcChannel.STREAM_ABORT_TYPE + "\""))
                .count();
        assertEquals(1, aborts);
        assertTrue(ack.isCompletedExceptionally());

        a.shutdown();
        b.shutdown();
    }

    @Test
    void activeStreamSurvivesOriginalDeadlineViaRefresh() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.dropDone = true;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        b.registerStreamHandler("slow", String.class, String.class, (meta, bytes) -> "ok");
        byte[] payload = "refresh-me".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<String> ack = a.sendStream("slow", "m", payload, String.class,
                Duration.ofSeconds(20));
        assertEquals(1, b.pendingStreamCount());
        UUID streamId = UUID.fromString(firstSentOfType(sa.sent,
                WsRpcChannel.STREAM_START_TYPE).get("payload").get("streamId").asText());

        backdateSlotReserve(b, Duration.ofSeconds(59).toNanos());
        b.onBinaryMessage(new FileTransferHeader(streamId, 0).encodeFrame(payload, 0, payload.length));
        Thread.sleep(1500);
        assertEquals(1, b.pendingStreamCount(), "progress must refresh the slot past its first deadline");

        String doneJson = firstSentOfType(sa.sent, WsRpcChannel.STREAM_DONE_TYPE).toString();
        b.onTextMessage(doneJson);
        assertEquals("ok", ack.get(5, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void slotDelayMathCapsAtWindowAndCeiling() throws Exception {
        var method = WsRpcChannel.class.getDeclaredMethod("slotExpiryDelayMillis", long.class);
        method.setAccessible(true);
        assertEquals(60000L, (long) method.invoke(null, 0L));
        assertEquals(60000L, (long) method.invoke(null, Duration.ofMillis(59900).toNanos()));
        long nearCeiling = (long) method.invoke(null, Duration.ofMillis(299900).toNanos());
        assertTrue(nearCeiling >= 0 && nearCeiling <= 1000, "ceiling caps the delay, got: " + nearCeiling);
        assertEquals(-1L, (long) method.invoke(null, Duration.ofSeconds(300).toNanos()));
        assertEquals(-1L, (long) method.invoke(null, Duration.ofSeconds(301).toNanos()));
    }

    @Test
    void refreshRearmsSlotAfterWindowElapses() throws Exception {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);
        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");

        UUID streamId = UUID.randomUUID();
        byte[] payload = new byte[] { 1 };
        WsRpcChannel.StreamStart start = new WsRpcChannel.StreamStart(streamId, "doc",
                b.getObjectMapper().valueToTree("m"), 1, FileChunkStreamer.sha256Hex(payload));
        b.onTextMessage(new ObjectMapper().writeValueAsString(
                WsMessage.<WsRpcChannel.StreamStart>create(WsRpcChannel.STREAM_START_TYPE)
                        .withPayload(start)));
        assertEquals(1, b.pendingStreamCount());

        backdateSlotReserve(b, Duration.ofMillis(59900).toNanos());
        b.onBinaryMessage(new FileTransferHeader(streamId, 0).encodeFrame(payload, 0, payload.length));

        assertEquals(1, b.pendingStreamCount(), "progress must re-arm the slot");
        Field timeoutsField = WsRpcChannel.class.getDeclaredField("streamTimeouts");
        timeoutsField.setAccessible(true);
        assertEquals(1, ((Map<?, ?>) timeoutsField.get(b)).size(), "exactly one timer must stay armed");

        b.shutdown();
    }

    @Test
    void slotCeilingExpiresDespiteProgress() throws Exception {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);
        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");

        UUID streamId = UUID.randomUUID();
        byte[] payload = new byte[] { 1 };
        WsRpcChannel.StreamStart start = new WsRpcChannel.StreamStart(streamId, "doc",
                b.getObjectMapper().valueToTree("m"), 1, FileChunkStreamer.sha256Hex(payload));
        b.onTextMessage(new ObjectMapper().writeValueAsString(
                WsMessage.<WsRpcChannel.StreamStart>create(WsRpcChannel.STREAM_START_TYPE)
                        .withPayload(start)));
        assertEquals(1, b.pendingStreamCount());

        backdateSlotReserve(b, Duration.ofSeconds(301).toNanos());
        b.onBinaryMessage(new FileTransferHeader(streamId, 0).encodeFrame(payload, 0, payload.length));

        assertEquals(0, b.pendingStreamCount());
        assertTrue(sb.sent.stream().anyMatch(t -> t.contains("ceiling")),
                "ceiling expiry must name the ceiling, sent: " + sb.sent);

        b.shutdown();
    }

    @Test
    void outOfRangeChunkFailsStreamFast() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.dropDone = true;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        final boolean[] ran = { false };
        b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> {
            ran[0] = true;
            return "ok";
        });
        byte[] payload = new byte[] { 1, 2, 3 };
        CompletableFuture<String> ack = a.sendStream("doc", "m", payload, String.class,
                Duration.ofSeconds(10));
        assertEquals(1, b.pendingStreamCount());
        UUID streamId = UUID.fromString(firstSentOfType(sa.sent,
                WsRpcChannel.STREAM_START_TYPE).get("payload").get("streamId").asText());

        b.onBinaryMessage(new FileTransferHeader(streamId, 1).encodeFrame(payload, 0, payload.length));

        Exception err = assertThrows(Exception.class, () -> ack.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null && err.getCause().getMessage().contains("outside 0..0"),
                "bounds failure must name the range, got: " + err.getCause());
        assertTrue(err.getCause().getMessage().contains(streamId.toString()));
        assertFalse(ran[0], "handler must not run on bounds failure");
        assertTrue(sb.sent.stream().anyMatch(t -> t.contains("\"error\":true")),
                "normal stream bounds failure must answer with an error, sent: " + sb.sent);

        a.shutdown();
        b.shutdown();
    }

    @Test
    void outOfRangeChunkFailsReplyRequestLocally() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sb.dropDone = true;
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        byte[] payload = new byte[5000];
        Arrays.fill(payload, (byte) 4);
        b.registerHandler("get-file", String.class, name -> new WsRpcChannel.StreamReply(payload, null));

        CompletableFuture<byte[]> res = a.sendRequest("get-file", "x", byte[].class,
                Duration.ofSeconds(10));
        JsonNode start = firstSentOfType(sb.sent, WsRpcChannel.STREAM_START_TYPE);
        assertNotNull(start, "reply stream must start, sent: " + sb.sent);
        UUID streamId = UUID.fromString(start.get("payload").get("streamId").asText());

        a.onBinaryMessage(new FileTransferHeader(streamId, 1).encodeFrame(payload, 0, payload.length));

        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null && err.getCause().getMessage().contains("outside 0..0"),
                "reply bounds failure must fail the request, got: " + err.getCause());
        assertTrue(sa.sent.stream().noneMatch(t -> t.contains("\"error\":true")),
                "reply failure must stay local with no frame, sent: " + sa.sent);

        a.shutdown();
        b.shutdown();
    }

    @Test
    void malformedReplyStartFailsRequestSilently() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        b.registerHandler("get-file", String.class, name -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new WsRpcChannel.StreamReply(new byte[] { 1 });
        });

        CompletableFuture<CompletableFuture<byte[]>> outer = CompletableFuture
                .supplyAsync(() -> a.sendRequest("get-file", "x", byte[].class, Duration.ofSeconds(15)));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        UUID requestId = UUID.fromString(firstSentOfType(sa.sent, "get-file").get("id").asText());

        WsMessage<String> malformed = WsMessage.<String>create(WsRpcChannel.STREAM_START_TYPE)
                .withPayload("oops");
        malformed.setResponseOf(requestId);
        a.onTextMessage(new ObjectMapper().writeValueAsString(malformed));

        assertEquals(1, sa.sent.size(), "no error frame may answer a reply start, sent: " + sa.sent);
        release.countDown();

        CompletableFuture<byte[]> res = outer.get(5, TimeUnit.SECONDS);
        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null && err.getCause().getMessage().contains("Malformed reply"),
                "malformed reply start must fail the request, got: " + err.getCause());

        a.shutdown();
        b.shutdown();
    }
}
