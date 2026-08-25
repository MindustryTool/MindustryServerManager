package plugin.graph.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSchedulerTest {

    private final AtomicLong now = new AtomicLong(0);
    private final GraphScheduler scheduler = new GraphScheduler(now::get);

    private void advanceSeconds(double seconds) {
        now.addAndGet((long) (seconds * 1_000_000_000L));
    }

    @Test
    void oneShotFiresOnceAfterDelay() {
        List<String> fired = new ArrayList<>();
        GraphScheduler.Handle handle = scheduler.once(1.0, () -> fired.add("x"));

        assertTrue(handle.isActive());
        assertEquals(0, scheduler.drainDue());
        assertTrue(fired.isEmpty());

        advanceSeconds(1.0);
        assertEquals(1, scheduler.drainDue());
        assertEquals(List.of("x"), fired);
        assertFalse(scheduler.pendingCount() > 0);

        advanceSeconds(10.0);
        assertEquals(0, scheduler.drainDue());
        assertEquals(List.of("x"), fired);
        assertFalse(handle.isActive());
    }

    @Test
    void repeatingFiresOnIntervalUntilCancelled() {
        List<Integer> fired = new ArrayList<>();
        GraphScheduler.Handle handle = scheduler.every(0.5, () -> fired.add(fired.size()));

        advanceSeconds(0.5);
        scheduler.drainDue();
        advanceSeconds(0.5);
        scheduler.drainDue();
        advanceSeconds(0.5);
        scheduler.drainDue();
        assertEquals(3, fired.size());
        assertTrue(handle.isActive());

        handle.cancel();
        assertFalse(handle.isActive());
        advanceSeconds(5.0);
        assertEquals(0, scheduler.drainDue());
        assertEquals(3, fired.size());
    }

    @Test
    void sameDeadlineTasksRunInScheduleOrder() {
        List<String> order = new ArrayList<>();
        scheduler.once(2.0, () -> order.add("first"));
        scheduler.once(1.0, () -> order.add("early"));
        scheduler.once(2.0, () -> order.add("second"));

        advanceSeconds(2.0);
        scheduler.drainDue();
        assertEquals(List.of("early", "first", "second"), order);
    }

    @Test
    void cancelBeforeFirePreventsRun() {
        List<String> fired = new ArrayList<>();
        GraphScheduler.Handle handle = scheduler.once(1.0, () -> fired.add("nope"));
        handle.cancel();
        handle.cancel();

        advanceSeconds(2.0);
        assertEquals(0, scheduler.drainDue());
        assertTrue(fired.isEmpty());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void cancelAllStopsEverything() {
        List<String> fired = new ArrayList<>();
        scheduler.every(0.25, () -> fired.add("a"));
        scheduler.once(1.0, () -> fired.add("b"));
        scheduler.cancelAll();

        advanceSeconds(5.0);
        assertEquals(0, scheduler.drainDue());
        assertTrue(fired.isEmpty());
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.every(0, () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.once(-1.0, () -> { }));
        assertThrows(NullPointerException.class,
                () -> scheduler.once(1.0, null));
    }

    @Test
    void taskThrownExceptionDoesNotLoseOtherTimers() {
        List<String> fired = new ArrayList<>();
        scheduler.once(1.0, () -> { throw new IllegalStateException("boom"); });
        scheduler.once(1.0, () -> fired.add("survivor"));

        advanceSeconds(1.0);
        assertThrows(IllegalStateException.class, scheduler::drainDue);
        assertTrue(fired.isEmpty());

        scheduler.drainDue();
        assertEquals(List.of("survivor"), fired);
    }
}
