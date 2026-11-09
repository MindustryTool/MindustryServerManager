package gateway.client;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundInterleaveTest {

    @Test
    void controlCutsInBetweenBinaryFrames() {
        OutboundInterleave<String> q = new OutboundInterleave<>();
        for (int i = 0; i < 10; i++) {
            q.offer("b" + i, true);
        }
        q.offer("control", false);
        List<String> order = drain(q);
        assertEquals(11, order.size());
        int idx = order.indexOf("control");
        assertTrue(idx >= 0 && idx <= 3, "control within 3 frames, idx=" + idx + " order=" + order);
        assertEquals(10, order.stream().filter(s -> s.startsWith("b")).count());
    }

    @Test
    void binaryProgressesUnderControlBurst() {
        OutboundInterleave<String> q = new OutboundInterleave<>();
        for (int i = 0; i < 6; i++) {
            q.offer("c" + i, false);
        }
        for (int i = 0; i < 6; i++) {
            q.offer("b" + i, true);
        }
        List<String> order = drain(q);
        int firstBinary = order.stream().filter(s -> s.startsWith("b")).mapToInt(order::indexOf).min()
                .orElse(-1);
        assertTrue(firstBinary >= 0 && firstBinary <= 3,
                "binary after at most 3 controls, order=" + order);
    }

    @Test
    void drainsFullyWithNoPeerKind() {
        OutboundInterleave<String> q = new OutboundInterleave<>();
        q.offer("b0", true);
        q.offer("b1", true);
        assertEquals(List.of("b0", "b1"), drain(q));
        assertTrue(q.isEmpty());
    }

    @Test
    void streamEnvelopesClassifiedAsBinaryLane() {
        assertTrue(OutboundInterleave.isStreamEnvelope("{\"kind\":\"stream-start\"}"));
        assertTrue(OutboundInterleave.isStreamEnvelope("{\"kind\":\"stream-done\"}"));
        assertTrue(OutboundInterleave.isStreamEnvelope("{\"kind\":\"stream-reply-start\"}"));
        assertTrue(OutboundInterleave.isStreamEnvelope("{\"kind\":\"stream-reply-done\"}"));
        assertTrue(!OutboundInterleave.isStreamEnvelope("{\"kind\":\"response\"}"));
        assertTrue(!OutboundInterleave.isStreamEnvelope("{\"kind\":\"event\"}"));
        assertTrue(!OutboundInterleave.isStreamEnvelope("plain text"));
        assertTrue(!OutboundInterleave.isStreamEnvelope("{\"kind\":\"stream-abort\"}"));
    }

    private static List<String> drain(OutboundInterleave<String> q) {
        List<String> out = new ArrayList<>();
        String item;
        while ((item = q.pollNext()) != null) {
            out.add(item);
            q.sent(item.startsWith("b"));
        }
        return out;
    }
}
