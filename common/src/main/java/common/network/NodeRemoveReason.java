package common.network;

public enum NodeRemoveReason {
    NO_PLAYER,
    USER_REQUEST,
    NOT_RESPONSE,
    NOT_CONNECTED,
    SOCKET_DISCONNECT,
    PROCESS_KILLED,
    OLD,
    CONFIG_DRIFT,
    UNKNOWN,
}
