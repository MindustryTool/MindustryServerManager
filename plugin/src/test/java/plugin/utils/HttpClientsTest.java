package plugin.utils;

import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpClientsTest {

    @Test
    void testSharedClientSingletonReuse() {
        HttpClient client1 = HttpClients.shared();
        HttpClient client2 = HttpClients.shared();

        assertSame(client1, client2, "HttpClients.shared() must return the same singleton instance");
        assertEquals(HttpClient.Redirect.NORMAL, client1.followRedirects());
        assertTrue(client1.connectTimeout().isPresent());
        assertEquals(Duration.ofSeconds(10), client1.connectTimeout().get());
    }
}
