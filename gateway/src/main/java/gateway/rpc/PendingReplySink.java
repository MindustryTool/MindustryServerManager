package gateway.rpc;

import java.util.UUID;

interface ReplySink {
    void failReply(UUID replyRequestId, String detail);
}
