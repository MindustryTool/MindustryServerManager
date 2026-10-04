package server.service.proxy;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

public class ProxySourceTest {

    @Test
    public void testProxyScrapePlainTextParsing() {
        String plainText = "# Comment\n" +
                "192.168.1.1:8080\r\n" +
                "10.0.0.1:3128\n" +
                "invalid-line\n" +
                "1.2.3.4:99999\n";

        List<InetSocketAddress> list = ProxyScrapeSource.parsePlainText(plainText);
        assertEquals(2, list.size());
        assertEquals("192.168.1.1", list.get(0).getHostString());
        assertEquals(8080, list.get(0).getPort());
        assertEquals("10.0.0.1", list.get(1).getHostString());
        assertEquals(3128, list.get(1).getPort());
    }

    @Test
    public void testGeonodeJsonParsing() {
        String json = "{\"data\":[{\"ip\":\"102.132.201.202\",\"port\":\"80\"},{\"ip\":\"65.21.201.149\",\"port\":8081}],\"total\":2}";

        List<InetSocketAddress> list = GeonodeSource.parseJson(json, new ObjectMapper());
        assertEquals(2, list.size());
        assertEquals("102.132.201.202", list.get(0).getHostString());
        assertEquals(80, list.get(0).getPort());
        assertEquals("65.21.201.149", list.get(1).getHostString());
        assertEquals(8081, list.get(1).getPort());
    }

    @Test
    public void testHProxyUsesPlainTextFormat() {
        HProxySource source = new HProxySource();
        assertEquals("hproxy", source.name());

        String text = "1.2.3.4:80\n5.6.7.8:8080";
        List<InetSocketAddress> list = ProxyScrapeSource.parsePlainText(text);
        assertEquals(2, list.size());
    }
}
