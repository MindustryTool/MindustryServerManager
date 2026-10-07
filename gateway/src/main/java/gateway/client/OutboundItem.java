package gateway.client;

public sealed interface OutboundItem permits SendFrame, PoisonPill {
}
