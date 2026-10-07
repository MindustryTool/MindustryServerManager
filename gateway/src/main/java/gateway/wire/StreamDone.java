package gateway.rpc;

import java.util.UUID;

public record StreamDone(
        UUID streamId,
        String sha256) {
}
