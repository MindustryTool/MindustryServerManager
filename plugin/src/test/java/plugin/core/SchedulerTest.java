package plugin.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import plugin.annotations.MainThread;
import plugin.annotations.Schedule;

public class SchedulerTest {

    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new Scheduler();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    public static class Dummy {
        final AtomicInteger runs = new AtomicInteger();
        CountDownLatch latch;

        @Schedule(fixedRate = 0)
        public void zeroFixedRate() {
        }

        @Schedule(fixedDelay = 0)
        public void zeroFixedDelay() {
        }

        @MainThread
        @Schedule(fixedDelay = 0)
        public void zeroFixedDelayOnMainThread() {
        }

        @Schedule(delay = -5, unit = TimeUnit.SECONDS)
        public void negativeDelay() {
        }

        @Schedule(delay = -1)
        public void negativeOneDelay() {
        }

        @Schedule
        public void unset() {
            runs.incrementAndGet();
            if (latch != null) {
                latch.countDown();
            }
        }

        @Schedule(fixedRate = -5, fixedDelay = 5, unit = TimeUnit.SECONDS)
        public void strictInvalid() {
        }

        @Schedule(delay = 0)
        public void validDelayZero() {
            runs.incrementAndGet();
            if (latch != null) {
                latch.countDown();
            }
        }

        @Schedule(fixedDelay = 50, unit = TimeUnit.MILLISECONDS)
        public void validFixedDelay() {
            runs.incrementAndGet();
            if (latch != null) {
                latch.countDown();
            }
        }

        @Schedule(fixedRate = 50, unit = TimeUnit.MILLISECONDS)
        public void validFixedRate() {
            runs.incrementAndGet();
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    private Schedule scheduleFor(String name) throws Exception {
        Method method = Dummy.class.getDeclaredMethod(name);
        return method.getAnnotation(Schedule.class);
    }

    private Method methodFor(String name) throws Exception {
        return Dummy.class.getDeclaredMethod(name);
    }

    @Test
    void zeroFixedRateThrows() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> scheduler.process(scheduleFor("zeroFixedRate"), new Dummy(), methodFor("zeroFixedRate")));
        assertTrue(e.getMessage().contains("Dummy.zeroFixedRate"));
        assertTrue(e.getMessage().contains("fixedRate=0"));
        assertTrue(e.getMessage().contains("MILLISECONDS"));
    }

    @Test
    void zeroFixedDelayThrows() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> scheduler.process(scheduleFor("zeroFixedDelay"), new Dummy(), methodFor("zeroFixedDelay")));
        assertTrue(e.getMessage().contains("Dummy.zeroFixedDelay"));
        assertTrue(e.getMessage().contains("fixedDelay=0"));
        assertTrue(e.getMessage().contains("MILLISECONDS"));
    }

    @Test
    void zeroFixedDelayOnMainThreadThrowsBeforeTimer() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> scheduler.process(scheduleFor("zeroFixedDelayOnMainThread"), new Dummy(),
                        methodFor("zeroFixedDelayOnMainThread")));
        assertTrue(e.getMessage().contains("zeroFixedDelayOnMainThread"));
        assertTrue(e.getMessage().contains("fixedDelay=0"));
    }

    @Test
    void negativeExplicitDelayThrows() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> scheduler.process(scheduleFor("negativeDelay"), new Dummy(), methodFor("negativeDelay")));
        assertTrue(e.getMessage().contains("negativeDelay"));
        assertTrue(e.getMessage().contains("delay=-5"));
        assertTrue(e.getMessage().contains("SECONDS"));
    }

    @Test
    void explicitMinusOneDelayThrows() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> scheduler.process(scheduleFor("negativeOneDelay"), new Dummy(), methodFor("negativeOneDelay")));
        assertTrue(e.getMessage().contains("negativeOneDelay"));
        assertTrue(e.getMessage().contains("delay=-1"));
    }

    @Test
    void emptyRunsImmediately() throws Exception {
        Dummy dummy = new Dummy();
        dummy.latch = new CountDownLatch(1);
        scheduler.process(scheduleFor("unset"), dummy, methodFor("unset"));
        assertTrue(dummy.latch.await(2, TimeUnit.SECONDS));
        assertTrue(dummy.runs.get() >= 1);
    }

    @Test
    void strictInvalidNonSelectedFieldThrows() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> scheduler.process(scheduleFor("strictInvalid"), new Dummy(), methodFor("strictInvalid")));
        assertTrue(e.getMessage().contains("strictInvalid"));
        assertTrue(e.getMessage().contains("fixedRate=-5"));
    }

    @Test
    void validOneTimeZeroRuns() throws Exception {
        Dummy dummy = new Dummy();
        dummy.latch = new CountDownLatch(1);
        scheduler.process(scheduleFor("validDelayZero"), dummy, methodFor("validDelayZero"));
        assertTrue(dummy.latch.await(2, TimeUnit.SECONDS));
        assertTrue(dummy.runs.get() >= 1);
    }

    @Test
    void validFixedDelayWithUnsetInitialDelayRunsSoon() throws Exception {
        Dummy dummy = new Dummy();
        dummy.latch = new CountDownLatch(1);
        scheduler.process(scheduleFor("validFixedDelay"), dummy, methodFor("validFixedDelay"));
        assertTrue(dummy.latch.await(2, TimeUnit.SECONDS));
        assertTrue(dummy.runs.get() >= 1);
    }

    @Test
    void validFixedRateRunsSoon() throws Exception {
        Dummy dummy = new Dummy();
        dummy.latch = new CountDownLatch(1);
        scheduler.process(scheduleFor("validFixedRate"), dummy, methodFor("validFixedRate"));
        assertTrue(dummy.latch.await(2, TimeUnit.SECONDS));
        assertTrue(dummy.runs.get() >= 1);
    }
}
