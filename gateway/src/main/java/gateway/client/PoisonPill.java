package gateway.client;

public enum PoisonPill implements OutboundItem {
    POISON;

    public static PoisonPill poison() {
        return POISON;
    }
}
