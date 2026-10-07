package gateway.client;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

/**
 * Dials one socket and hands inbound events to the given listener.
 * The default implementation dials over HTTP; tests supply fakes.
 */
public interface WsDialer {

    CompletableFuture<WebSocket> dial(WebSocket.Listener listener);
}
