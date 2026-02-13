package dto;

public enum ServerStatus {
    ONLINE,
    PAUSED,
    DISCONNECT,
    STOP,
    UNKNOWN,
    NOT_RESPONSE,
    UNSET,
    OFFLINE//
    ;

    public boolean isOnline() {
        return this == ONLINE || this == PAUSED;
    }
}
