package gateway.client;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;

public record SendContext(
        WebSocket socket,
        Duration sendTimeout,
        Transport.Events events) {

    public SendContext {
        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(sendTimeout, "sendTimeout");
        Objects.requireNonNull(events, "events");
    }
}
