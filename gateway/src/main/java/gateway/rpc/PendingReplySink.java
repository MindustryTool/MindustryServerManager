package gateway.rpc;

import java.util.UUID;

public interface PendingReplySink {
    void failReply(UUID replyRequestId, String detail);
}
