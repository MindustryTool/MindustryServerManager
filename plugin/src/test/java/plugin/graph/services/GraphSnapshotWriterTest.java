package plugin.graph.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSnapshotWriterTest {

    @TempDir
    Path tempDir;

    private static final String LINE =
            "{\"event\":\"suspended\",\"execution\":1,\"graph\":\"g\",\"vars\":{}}";

    @Test
    void flagOffWritesNothingAndAllocatesNoIoTask() throws Exception {
        Path file = tempDir.resolve("snap.jsonl");
        GraphSnapshotWriter writer = new GraphSnapshotWriter(file, () -> false);
        try {
            writer.hook().onExecutionEvent(1, "g", "suspended", null, Map.of());
            writer.hook().onExecutionEvent(2, "g", "resumed", null, Map.of());
            Thread.sleep(150);
            assertFalse(Files.exists(file), "disabled writer must not create files");
        } finally {
            writer.close();
        }
    }

    @Test
    void flagOnWritesJsonLinesOffTheCallingThread() throws Exception {
        Path file = tempDir.resolve("snap.jsonl");
        GraphSnapshotWriter writer = new GraphSnapshotWriter(file, () -> true);
        try {
            String callingThread = Thread.currentThread().getName();
            List<String> seenThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
            DebugHookHook hook = new DebugHookHook(writer, seenThreads);
            hook.onExecutionEvent(7, "welcome-flow", "suspended", "park",
                    Map.of("who", "alice"));
            hook.onExecutionEvent(7, "welcome-flow", "resumed", null, Map.of());

            for (int i = 0; i < 50 && !Files.exists(file); i++) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertTrue(Files.exists(file), "snapshot file must appear");
            List<String> lines = Files.readAllLines(file);
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).contains("\"event\":\"suspended\""));
            assertTrue(lines.get(0).contains("\"node\":\"park\""));
            assertTrue(lines.get(0).contains("who\":\"alice\""));
            assertTrue(lines.get(1).contains("\"event\":\"resumed\""));

            // The write happened off the calling thread: force one more line
            // and verify the io thread name recorded by the writer task.
            writer.appendJsonLine("{\"probe\":true}");
            for (int i = 0; i < 50; i++) {
                TimeUnit.MILLISECONDS.sleep(20);
                if (Files.readAllLines(file).size() == 3) {
                    break;
                }
            }
            assertEquals(3, Files.readAllLines(file).size());
            assertTrue(seenThreads.isEmpty(),
                    "hook must not do file IO on the calling thread");
            assertTrue(!callingThread.equals("graph-snapshot-io"));
        } finally {
            writer.close();
        }
    }

    /** Thin wrapper exposing thread observations around the real hook. */
    private static final class DebugHookHook implements graph.runtime.DebugHook {
        private final GraphSnapshotWriter writer;
        private final List<String> seenThreads;

        DebugHookHook(GraphSnapshotWriter writer, List<String> seenThreads) {
            this.writer = writer;
            this.seenThreads = seenThreads;
        }

        @Override
        public void onExecutionEvent(long executionId, String graphId,
                String event, String detailNodeId,
                Map<String, Object> variables) {
            if (Thread.currentThread().getName().equals("graph-snapshot-io")) {
                seenThreads.add(Thread.currentThread().getName());
            }
            writer.hook().onExecutionEvent(executionId, graphId, event,
                    detailNodeId, variables);
        }
    }
}
