package gateway.client;

public enum Control implements QueueItem {
    POISON;

    public static Control poison() {
        return POISON;
    }
}
