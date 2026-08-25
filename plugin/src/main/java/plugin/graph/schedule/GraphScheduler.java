package plugin.graph.schedule;

import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.LongSupplier;

/**
 * Tick-driven scheduler for graph timers. All work runs on the calling thread
 * of {@link #drainDue()} (the server/main thread); no worker threads are
 * created. Real deployments call {@code drainDue()} from the plugin tick
 * hook; tests drive it manually with a fake nanosecond clock.
 */
public final class GraphScheduler {

    public interface Handle {

        void cancel();

        boolean isActive();
    }

    private static final class Task implements Comparable<Task> {
        final Runnable action;
        final long periodNanos;
        final long seq;
        long nextDueNanos;
        boolean cancelled;
        boolean done;

        Task(Runnable action, long periodNanos, long nextDueNanos, long seq) {
            this.action = action;
            this.periodNanos = periodNanos;
            this.nextDueNanos = nextDueNanos;
            this.seq = seq;
        }

        @Override
        public int compareTo(Task other) {
            int byTime = Long.compare(nextDueNanos, other.nextDueNanos);
            return byTime != 0 ? byTime : Long.compare(seq, other.seq);
        }
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final LongSupplier clock;
    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private long seqGenerator;

    public GraphScheduler(LongSupplier nanoClock) {
        this.clock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public Handle once(double delaySeconds, Runnable action) {
        return schedule(delaySeconds, 0.0, action);
    }

    public Handle every(double intervalSeconds, Runnable action) {
        if (!(intervalSeconds > 0.0)) {
            throw new IllegalArgumentException("interval must be > 0");
        }
        return schedule(intervalSeconds, intervalSeconds, action);
    }

    private Handle schedule(double delaySeconds, double periodSeconds, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!(delaySeconds >= 0.0)) {
            throw new IllegalArgumentException("delay must be >= 0");
        }
        Task task = new Task(action, (long) (periodSeconds * NANOS_PER_SECOND),
                clock.getAsLong() + (long) (delaySeconds * NANOS_PER_SECOND),
                seqGenerator++);
        queue.add(task);
        return new Handle() {
            @Override
            public void cancel() {
                task.cancelled = true;
            }

            @Override
            public boolean isActive() {
                return !task.cancelled && !task.done;
            }
        };
    }

    public int drainDue() {
        int ran = 0;
        long now = clock.getAsLong();
        while (!queue.isEmpty()) {
            Task top = queue.peek();
            if (top.cancelled) {
                queue.poll();
                continue;
            }
            if (top.nextDueNanos > now) {
                break;
            }
            queue.poll();
            if (!top.cancelled) {
                top.action.run();
                ran++;
            }
            if (top.periodNanos > 0 && !top.cancelled) {
                top.nextDueNanos = now + top.periodNanos;
                queue.add(top);
            } else {
                top.done = true;
            }
        }
        return ran;
    }

    public int pendingCount() {
        int count = 0;
        for (Task task : queue) {
            if (!task.cancelled && !task.done) {
                count++;
            }
        }
        return count;
    }

    public void cancelAll() {
        for (Task task : queue) {
            task.cancelled = true;
        }
        queue.clear();
    }
}
