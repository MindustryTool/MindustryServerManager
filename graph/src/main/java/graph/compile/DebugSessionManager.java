package graph.compile;

import graph.runtime.DebugHook;
import graph.runtime.GraphCancelledException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Cooperative debug session manager. When a node entry hits an armed
 * breakpoint or step mode, the manager captures a value snapshot and hands a
 * {@link PauseContext} to the registered handler; execution stays blocked
 * until the handler resumes or cancels it.
 */
public final class DebugSessionManager implements DebugHook {

    public static final class PauseContext {
        private final long executionId;
        private final String graphId;
        private final String nodeId;
        private final Map<String, Object> variables;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean cancelRequested;

        PauseContext(long executionId, String graphId, String nodeId,
                Map<String, Object> variables) {
            this.executionId = executionId;
            this.graphId = graphId;
            this.nodeId = nodeId;
            this.variables = variables;
        }

        public long executionId() {
            return executionId;
        }

        public String graphId() {
            return graphId;
        }

        public String nodeId() {
            return nodeId;
        }

        public Map<String, Object> variables() {
            return variables;
        }

        public void resume() {
            latch.countDown();
        }

        public void cancelExecution() {
            cancelRequested = true;
            latch.countDown();
        }

        boolean awaitPause() throws InterruptedException {
            latch.await();
            return !cancelRequested;
        }
    }

    private final List<String> breakpoints = new CopyOnWriteArrayList<>();
    private final Map<Long, java.util.Set<String>> pausedExecutions =
            new ConcurrentHashMap<>();
    private volatile Consumer<PauseContext> pauseHandler = p -> p.resume();
    private volatile boolean attached;
    private volatile boolean steppingAll;
    private volatile int stepsRemaining;

    public void attach() {
        attached = true;
    }

    public void detach() {
        attached = false;
        steppingAll = false;
        stepsRemaining = 0;
    }

    public void addBreakpoint(String nodeId) {
        breakpoints.add(nodeId);
    }

    public void removeBreakpoint(String nodeId) {
        breakpoints.remove(nodeId);
    }

    /**
     * Steps: pauses on the next {@code count} node entries regardless of
     * breakpoints.
     */
    public void step(int count) {
        steppingAll = true;
        stepsRemaining = count;
    }

    public void onPause(Consumer<PauseContext> handler) {
        this.pauseHandler = handler;
    }

    public boolean isPaused(long executionId, String nodeId) {
        java.util.Set<String> nodes = pausedExecutions.get(executionId);
        return nodes != null && nodes.contains(nodeId);
    }

    @Override
    public void onNodeEnter(long executionId, String graphId, int generation,
            String nodeId, Map<String, Object> variableSnapshot) {
        if (!attached) {
            return;
        }
        boolean breakpointHit = breakpoints.contains(nodeId);
        boolean stepHit = steppingAll && stepsRemaining > 0;
        if (!breakpointHit && !stepHit) {
            return;
        }
        if (stepHit) {
            stepsRemaining--;
            if (stepsRemaining == 0) {
                steppingAll = false;
            }
        }
        PauseContext ctx = new PauseContext(executionId, graphId, nodeId,
                Map.copyOf(variableSnapshot));
        pausedExecutions.computeIfAbsent(executionId,
                k -> ConcurrentHashMap.newKeySet()).add(nodeId);
        try {
            pauseHandler.accept(ctx);
            if (!ctx.awaitPause()) {
                throw new GraphCancelledException(
                        "debug session cancelled execution " + executionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphCancelledException("debug wait interrupted");
        } finally {
            java.util.Set<String> nodes = pausedExecutions.get(executionId);
            if (nodes != null) {
                nodes.remove(nodeId);
            }
        }
    }
}
