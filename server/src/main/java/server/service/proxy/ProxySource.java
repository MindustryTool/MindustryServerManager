package server.service.proxy;

import java.net.InetSocketAddress;
import java.util.List;

public interface ProxySource {
    /**
     * @return Human-readable name of the proxy source.
     */
    String name();

    /**
     * Fetches public HTTP proxies from this source.
     *
     * @return List of parsed InetSocketAddress proxies.
     * @throws Exception if fetching or parsing fails.
     */
    List<InetSocketAddress> fetch() throws Exception;
}
