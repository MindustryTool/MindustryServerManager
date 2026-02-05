package dto;

public enum ServerStatus {
    UNSET,
    STOP,
    DISCONNECT,
    ONLINE,
    OFFLINE,
    NOT_RESPONSE,
    PAUSED,
    UNKNOWN;

    public boolean isOnline() {
        return this == ONLINE || this == PAUSED;
    }
}
