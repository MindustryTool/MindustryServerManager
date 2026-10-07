package gateway.client;

public sealed interface QueueItem permits SendOp, Control {
}
