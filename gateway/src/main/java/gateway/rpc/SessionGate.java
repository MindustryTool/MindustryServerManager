package gateway.rpc;

import java.util.concurrent.CompletableFuture;

import gateway.session.WsSession;

interface SessionGate {
    CompletableFuture<WsSession> awaitOpen();

    WsSession current();
}
