package plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import arc.Core;
import arc.mock.MockApplication;
import arc.util.Log;
import arc.util.Log.LogHandler;
import arc.util.Log.LogLevel;
import arc.util.TaskQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TaskQueueMonitorTest {

    private LogHandler originalLogger;
    private List<String> loggedWarnings;

    @BeforeEach
    void setUp() {
        originalLogger = Log.logger;
        loggedWarnings = new ArrayList<>();
        Log.logger = (level, text) -> {
            if (level == LogLevel.warn) {
                loggedWarnings.add(text);
            }
        };
    }

    @AfterEach
    void tearDown() {
        Log.logger = originalLogger;
        Core.app = null;
    }

    @Test
    void resolvesOriginFromLambdaAndClasses() {
        Runnable lambda = () -> {};
        String origin = TaskQueueMonitor.resolveOrigin(lambda);
        assertEquals(getClass().getName(), origin);

        Runnable named = new NamedTask();
        assertEquals(NamedTask.class.getName(), TaskQueueMonitor.resolveOrigin(named));

        assertEquals("null", TaskQueueMonitor.resolveOrigin(null));
    }

    @Test
    void computesHistogramCorrectly() {
        List<Runnable> runnables = List.of(
                () -> {},
                () -> {},
                new NamedTask()
        );

        Map<String, Long> histogram = TaskQueueMonitor.computeHistogram(runnables);

        assertEquals(2L, histogram.get(getClass().getName()));
        assertEquals(1L, histogram.get(NamedTask.class.getName()));
    }

    @Test
    void doesNotLogWhenQueueBelowThreshold() {
        TaskQueue queue = new TaskQueue();
        Field innerField = TaskQueueMonitor.findField(TaskQueue.class, "runnables");
        assertNotNull(innerField);

        TaskQueueMonitor monitor = new TaskQueueMonitor(queue, innerField, 5);
        queue.post(() -> {});
        queue.post(() -> {});

        monitor.checkQueueSize();

        assertTrue(loggedWarnings.isEmpty(), "Expected no warnings when queue size <= threshold");
    }

    @Test
    void logsSpikeAndBreakdownWhenQueueExceedsThreshold() {
        TaskQueue queue = new TaskQueue();
        Field innerField = TaskQueueMonitor.findField(TaskQueue.class, "runnables");
        assertNotNull(innerField);

        TaskQueueMonitor monitor = new TaskQueueMonitor(queue, innerField, 2);
        queue.post(() -> {});
        queue.post(() -> {});
        queue.post(new NamedTask());

        monitor.checkQueueSize();

        assertEquals(3, loggedWarnings.size());
        assertTrue(loggedWarnings.get(0).contains("Core.app TaskQueue spiked to 3 tasks!"));
        assertTrue(loggedWarnings.stream().anyMatch(w -> w.contains(getClass().getName()) && w.contains("2 tasks")));
        assertTrue(loggedWarnings.stream().anyMatch(w -> w.contains(NamedTask.class.getName()) && w.contains("1 tasks")));
    }

    @Test
    void initFailsLoudWhenCoreAppIsNull() {
        Core.app = null;
        TaskQueueMonitor monitor = new TaskQueueMonitor();

        IllegalStateException ex = assertThrows(IllegalStateException.class, monitor::init);
        assertTrue(ex.getMessage().contains("Core.app is null"));
    }

    @Test
    void initFailsLoudWhenRunnablesFieldMissing() {
        Core.app = new MockApplication();
        TaskQueueMonitor monitor = new TaskQueueMonitor();

        IllegalStateException ex = assertThrows(IllegalStateException.class, monitor::init);
        assertTrue(ex.getMessage().contains("Could not find 'runnables' field"));
    }

    @Test
    void initSucceedsWithHeadlessApplicationStructure() {
        HeadlessAppStub stub = new HeadlessAppStub();
        Core.app = stub;

        TaskQueueMonitor monitor = new TaskQueueMonitor();
        monitor.init();

        assertNotNull(monitor.getTaskQueue());
        assertNotNull(monitor.getInnerRunnablesField());
        assertEquals(stub.runnables, monitor.getTaskQueue());
    }

    private static class NamedTask implements Runnable {
        @Override
        public void run() {}
    }

    private static class HeadlessAppStub extends MockApplication {
        protected final TaskQueue runnables = new TaskQueue();
    }
}
