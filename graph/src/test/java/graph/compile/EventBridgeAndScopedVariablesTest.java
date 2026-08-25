package graph.compile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNull;

import graph.runtime.EventBridgeManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBridgeAndScopedVariablesTest {

    @Test
    void bridgeSubscribesOnFirstAcquireOnly() {
        AtomicInteger subscriptions = new AtomicInteger();
        List<String> closed = new ArrayList<>();
        EventBridgeManager bridges = new EventBridgeManager(eventId -> {
            subscriptions.incrementAndGet();
            return () -> closed.add(eventId);
        });

        assertTrue(bridges.refCount("mindustry.event.player.join") == 0);
        assertFalse(bridges.isSubscribed("mindustry.event.player.join"));

        bridges.acquire("mindustry.event.player.join");
        bridges.acquire("mindustry.event.player.join");
        assertEquals(2, bridges.refCount("mindustry.event.player.join"));
        assertEquals(1, subscriptions.get(), "second acquire must not re-subscribe");

        bridges.release("mindustry.event.player.join");
        assertTrue(bridges.isSubscribed("mindustry.event.player.join"),
                "still referenced once");

        bridges.release("mindustry.event.player.join");
        assertEquals(0, bridges.refCount("mindustry.event.player.join"));
        assertFalse(bridges.isSubscribed("mindustry.event.player.join"));
        assertEquals(List.of("mindustry.event.player.join"), closed,
                "unsubscribe must run exactly once at refCount zero");
    }

    @Test
    void releaseUnknownIsNoOpAndReleaseAllClearsEverything() {
        List<String> closed = new ArrayList<>();
        EventBridgeManager bridges = new EventBridgeManager(id -> () -> closed.add(id));
        bridges.release("never.subscribed");

        bridges.acquire("a");
        bridges.acquire("b");
        bridges.releaseAll();
        assertEquals(List.of("a", "b"), closed);
        assertTrue(bridges.subscribedEvents().isEmpty());
    }

    @Test
    void subscriptionFailurePropagatesWithoutPartialState() {
        EventBridgeManager bridges = new EventBridgeManager(id -> {
            throw new IllegalStateException("no bus");
        });
        assertThrows(IllegalStateException.class, () -> bridges.acquire("x"));
        assertFalse(bridges.isSubscribed("x"), "failed subscribe leaves no entry");
        assertEquals(0, bridges.refCount("x"));
    }

    @Test
    void scopedVariablesIsolateByScopeAndKey() {
        ScopedVariables vars = new ScopedVariables();

        vars.set(ScopedVariables.SERVER, null, "visits", 1);
        assertEquals(1, vars.get(ScopedVariables.SERVER, null, "visits"));

        vars.set(ScopedVariables.GRAPH, null, "count", 10);
        assertEquals(10, vars.get(ScopedVariables.GRAPH, null, "count"));

        vars.set(ScopedVariables.PLAYER, "Alice", "kills", 3);
        vars.set(ScopedVariables.PLAYER, "Bob", "kills", 5);
        assertEquals(3, vars.get(ScopedVariables.PLAYER, "Alice", "kills"));
        assertEquals(5, vars.get(ScopedVariables.PLAYER, "Bob", "kills"));

        vars.clearKey(ScopedVariables.PLAYER, "Alice");
        assertNull(vars.get(ScopedVariables.PLAYER, "Alice", "kills"));
        assertEquals(5, vars.get(ScopedVariables.PLAYER, "Bob", "kills"),
                "clearing one key must not touch siblings");
    }

    @Test
    void missingScopeKeyFallsBackToGlobalBucket() {
        ScopedVariables vars = new ScopedVariables();
        vars.set(ScopedVariables.TEAM, null, "score", 7);
        assertEquals(7, vars.get(ScopedVariables.TEAM, "", "score"));
        assertEquals(7, vars.get(ScopedVariables.TEAM, "__global__", "score"));
    }
}

