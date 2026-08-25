package plugin.graph;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import plugin.graph.services.GraphHttpService;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphHttpServiceTest {

    private static HttpServer server;
    private static int port;
    private static GraphHttpService service;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hello", exchange -> {
            byte[] bytes = "hello".getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/huge", exchange -> {
            byte[] chunk = new byte[1024];
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 64; i++) {
                    out.write(chunk);
                }
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/hello");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        final var capturedQuery = new Object() {
            String query = "";
        };
        server.createContext("/echo", exchange -> {
            capturedQuery.query = exchange.getRequestURI().getRawQuery();
            byte[] bytes = "ok".getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        port = server.getAddress().getPort();
        service = new GraphHttpService(1000.0, 1000, 1024, 1024, 500);
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private record Result(int status, String body, boolean success) {
    }

    private static Result get(GraphHttpService svc, String url,
                              Map<String, String> query) throws Exception {
        var result = svc.execute("k", "GET", url, Map.of(), query, null)
                .get(5, TimeUnit.SECONDS);
        return new Result(result.status(), result.body(), result.success());
    }

    @Test
    void simpleGetSucceeds() throws Exception {
        Result result = get(service, url("/hello"), null);
        assertEquals(200, result.status());
        assertTrue(result.success());
        assertEquals("hello", result.body());
    }

    @Test
    void queryParametersAppended() throws Exception {
        GraphHttpService fresh = new GraphHttpService(10_000.0, 10_000, 4096, 4096, 2000);
        Result result = get(fresh, url("/echo"), Map.of("a", "1", "b", "x y"));
        assertEquals(200, result.status(), () -> "body=" + result.body());
    }

    @Test
    void timeoutProducesFailureNotHang() throws Exception {
        var future = service.execute("t", "GET", url("/slow"), Map.of(),
                Map.of(), null);
        var result = future.get(5, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertEquals(599, result.status());
    }

    @Test
    void oversizedResponseRejectedWithTypedError() throws Exception {
        GraphHttpService strict = new GraphHttpService(10_000.0, 10_000, 16, 16, 500);
        var result = strict.execute("s", "GET", url("/huge"), Map.of(), Map.of(), null)
                .get(5, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertEquals(0, result.status());
        assertTrue(result.body().contains("exceeds"), () -> result.body());
    }

    @Test
    void rateLimiterBlocksFlood() throws Exception {
        GraphHttpService limited = new GraphHttpService(2.0, 2, 4096, 4096, 500);
        var first = limited.execute("f", "GET", url("/hello"), Map.of(), Map.of(), null);
        var second = limited.execute("f", "GET", url("/hello"), Map.of(), Map.of(), null);
        var third = limited.execute("f", "GET", url("/hello"), Map.of(), Map.of(), null);

        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
        var failed = third.get(2, TimeUnit.SECONDS);
        assertFalse(failed.success());
        assertEquals(0, failed.status());
        assertTrue(failed.body().contains("Rate limit"),
                () -> "body='" + failed.body() + "' status=" + failed.status());
    }
}
