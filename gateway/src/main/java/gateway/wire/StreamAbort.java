package gateway.wire;

import java.util.UUID;

public record StreamAbort(
        UUID streamId,
        String reason) {
}
