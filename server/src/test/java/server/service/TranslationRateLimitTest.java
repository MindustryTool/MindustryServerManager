package server.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;

import dto.TranslationResponseDto;
import gateway.session.WsSession;
import server.EnvConfig;
import server.service.translation.TranslationService;

class TranslationRateLimitTest {

    private static final int REQUESTS = 120;

    static class CountingTranslationService extends TranslationService {
        final AtomicInteger calls = new AtomicInteger();

        CountingTranslationService() {
            super(Caffeine.newBuilder().build());
        }

        @Override
        public TranslationResponseDto translate(String text, String targetLang) {
            calls.incrementAndGet();
            return new TranslationResponseDto("tr:" + text, "en");
        }
    }

    static class CapturingSession implements WsSession {
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void sendText(String text) {
            sent.add(text);
        }

        @Override
        public void sendBinary(ByteBuffer data) {
        }

        @Override
        public void close(int code, String reason) {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private GatewayService service;
    private CountingTranslationService translations;

    @BeforeEach
    void setUp() {
        EnvConfig env = new EnvConfig(
                new EnvConfig.DockerEnv("image", "/data", null, null),
                new EnvConfig.ServerConfig(true, "token", "/data", "ws://localhost/gateway", "http://localhost/"),
                "test-signing-key");
        translations = new CountingTranslationService();
        service = new GatewayService(new EventBus(), env, new GatewayClientLivenessTest.StubNodeManager(),
                translations, new PluginBundleService(new byte[] { 1 }, "test"));
    }

    private JsonNode callTranslate(GatewayService.GatewayClient client, CapturingSession session, String text)
            throws Exception {
        int before = session.sent.size();
        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"request\",\"type\":\"translate\","
                + "\"payload\":{\"text\":\"" + text + "\",\"targetLang\":\"vi\"}}";
        client.rpcChannel().onTextMessage(session, frame);

        long deadline = System.currentTimeMillis() + 5000;
        while (session.sent.size() == before) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("no translate response within timeout");
            }
            Thread.sleep(2);
        }

        return mapper.readTree(session.sent.get(session.sent.size() - 1)).get("payload");
    }

    @Test
    void underLimitCallIsServed() throws Exception {
        GatewayService.GatewayClient client = service.of(UUID.randomUUID());
        CapturingSession session = new CapturingSession();
        client.rpcChannel().onOpen(session);

        JsonNode payload = callTranslate(client, session, "hello");

        assertTrue(payload != null && !payload.isNull(), "first call must be translated");
        assertTrue(translations.calls.get() == 1, "first call delegates to TranslationService");
    }

    @Test
    void overLimitCallIsRejectedWithoutInvokingService() throws Exception {
        GatewayService.GatewayClient client = service.of(UUID.randomUUID());
        CapturingSession session = new CapturingSession();
        client.rpcChannel().onOpen(session);

        int rejected = 0;
        for (int i = 0; i < REQUESTS; i++) {
            JsonNode payload = callTranslate(client, session, "msg-" + i);
            if (payload == null || payload.isNull()) {
                rejected++;
            }
        }

        assertTrue(rejected > 0, "burst above the per-server budget must be rejected");
        assertTrue(translations.calls.get() < REQUESTS,
                "rejected calls must not reach TranslationService");
        assertTrue(translations.calls.get() == REQUESTS - rejected,
                "every non-rejected call must perform exactly one translation");
    }

    @Test
    void perServerBucketsAreIndependent() throws Exception {
        GatewayService.GatewayClient busy = service.of(UUID.randomUUID());
        CapturingSession busySession = new CapturingSession();
        busy.rpcChannel().onOpen(busySession);

        for (int i = 0; i < REQUESTS; i++) {
            callTranslate(busy, busySession, "busy-" + i);
        }

        GatewayService.GatewayClient other = service.of(UUID.randomUUID());
        CapturingSession otherSession = new CapturingSession();
        other.rpcChannel().onOpen(otherSession);

        JsonNode payload = callTranslate(other, otherSession, "fresh");

        assertTrue(payload != null && !payload.isNull(), "a different server must not be affected");
    }
}
