package plugin.graph.services;

import graph.runtime.DebugHook;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * File-backed JSON-line flow snapshot writer. Every append is gated by the
 * {@code graph.flowSnapshots} flag in {@code Core.settings}; when the flag is
 * off the writer does nothing and allocates nothing. Lines are written on a
 * dedicated daemon thread, never on the server main thread.
 */
public final class GraphSnapshotWriter implements AutoCloseable {

    public static final String SETTING_KEY = "graph.flowSnapshots";

    private final Path file;
    private final BooleanSupplier enabled;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "graph-snapshot-io");
        t.setDaemon(true);
        return t;
    });

    public GraphSnapshotWriter(Path file, BooleanSupplier enabledSupplier) {
        this.file = file;
        this.enabled = enabledSupplier;
    }

    /** Convenience factory reading the dedicated Core.settings boolean. */
    public static GraphSnapshotWriter forCoreSettings(Path file) {
        return new GraphSnapshotWriter(file,
                () -> arc.Core.settings.getBool(SETTING_KEY, false));
    }

    public void appendJsonLine(String json) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        io.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                try (BufferedWriter w = Files.newBufferedWriter(file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(json);
                    w.newLine();
                }
            } catch (IOException ignored) {
                // snapshot IO must never break gameplay
            }
        });
    }

    public String threadName() {
        return io.toString();
    }

    @Override
    public void close() {
        io.shutdownNow();
    }

    /** Adapts engine lifecycle events into snapshot lines. */
    public DebugHook hook() {
        return new DebugHook() {
            @Override
            public void onExecutionEvent(long executionId, String graphId,
                    String event, String detailNodeId,
                    Map<String, Object> variables) {
                if (!event.equals("suspended") && !event.equals("resumed")
                        && !event.equals("completed") && !event.equals("failed")) {
                    return;
                }
                StringBuilder sb = new StringBuilder("{\"event\":\"")
                        .append(event).append("\",\"execution\":").append(executionId)
                        .append(",\"graph\":\"").append(graphId).append('"');
                if (detailNodeId != null) {
                    sb.append(",\"node\":\"").append(detailNodeId).append('"');
                }
                sb.append(",\"vars\":{");
                boolean first = true;
                for (Map.Entry<String, Object> e : variables.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append('"').append(e.getKey()).append("\":\"")
                            .append(e.getValue()).append('"');
                }
                sb.append("}}");
                appendJsonLine(sb.toString());
            }
        };
    }
}
