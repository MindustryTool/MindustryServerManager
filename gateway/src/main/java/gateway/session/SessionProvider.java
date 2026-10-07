package gateway.session;

public interface SessionProvider {
    /** The current open session, or null when none is open. */
    WsSession current();
}
