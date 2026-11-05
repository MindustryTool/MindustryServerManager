package server.utils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import server.config.Const;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadExecutorsTest {

    @Test
    void virtualThreadExecutorExecutesTasksSuccessfully() throws Exception {
        ExecutorService executor = ThreadExecutors.createVirtualThreadExecutor();
        assertNotNull(executor);

        AtomicBoolean executed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        executor.execute(() -> {
            executed.set(true);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Task must complete within timeout");
        assertTrue(executed.get(), "Task flag must be set to true");
        assertFalse(executor.isShutdown(), "Executor must remain open after task");
    }

    @Test
    void constSharedSchedulerExecutesScheduledTasks() throws Exception {
        assertNotNull(Const.scheduler);
        assertFalse(Const.scheduler.isShutdown());

        CountDownLatch latch = new CountDownLatch(1);
        Const.scheduler.schedule(latch::countDown, 10, TimeUnit.MILLISECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Scheduled task must run");
    }
}
