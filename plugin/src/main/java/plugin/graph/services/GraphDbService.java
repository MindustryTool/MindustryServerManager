package plugin.graph.services;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * SQLite-backed database service for graph db-* nodes. All statement text is
 * parameterization-only: string literals, comments, and multi-statement text
 * are rejected before execution. Work runs on a dedicated single-thread
 * executor, never on the server main thread.
 */
public final class GraphDbService {

    public static List<String> validateSql(String sql) {
        List<String> problems = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            problems.add("sql must not be blank");
            return problems;
        }
        if (sql.contains("'")) {
            problems.add("string literals are forbidden; bind values with ?");
        }
        if (sql.contains("\"")) {
            problems.add("quoted identifiers/text are forbidden; bind values with ?");
        }
        if (sql.contains(";")) {
            problems.add("multiple statements are forbidden");
        }
        if (sql.contains("--") || sql.contains("/*")) {
            problems.add("comments are forbidden in statement text");
        }
        return problems;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private final Supplier<Connection> connectionSupplier;
    private final ExecutorService executor;
    private final long maxRows;

    public GraphDbService(ThrowingSupplier<Connection> connectionFactory, long maxRows) {
        this.connectionSupplier = () -> {
            try {
                return connectionFactory.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "graph-db");
            t.setDaemon(true);
            return t;
        });
        this.maxRows = maxRows;
    }

    public static GraphDbService forDatabase(Path sqliteFile, long maxRows) {
        return new GraphDbService(
                () -> DriverManager.getConnection("jdbc:sqlite:" + sqliteFile), maxRows);
    }

    public CompletableFuture<List<Map<String, Object>>> query(String sql,
            Map<String, Object> params) {
        List<String> problems = validateSql(sql);
        if (!problems.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(String.join("; ", problems)));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return runQuery(sql, params);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    public CompletableFuture<Integer> update(String kind, String table,
            Map<String, Object> row) {
        Objects.requireNonNull(table, "table");
        if (!validateSql(table).isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("invalid table name: " + table));
        }
        String sql = switch (kind == null ? "" : kind) {
            case "db-insert" -> insertSql(table, row);
            case "db-update" -> updateSql(table, row);
            case "db-delete" -> "DELETE FROM " + table + " WHERE id = ?";
            default -> null;
        };
        if (sql == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("unknown update kind: " + kind));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                try (Connection c = connectionSupplier.get();
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    List<Object> values = bindValues(kind, row);
                    for (int i = 0; i < values.size(); i++) {
                        ps.setObject(i + 1, values.get(i));
                    }
                    return ps.executeUpdate();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * Runs every operation on one connection; any failure rolls the whole
     * batch back.
     */
    public CompletableFuture<Integer> transaction(
            List<Map.Entry<String, Map<String, Object>>> statements) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                try (Connection c = connectionSupplier.get()) {
                    boolean oldAutoCommit = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        int total = 0;
                        for (Map.Entry<String, Map<String, Object>> entry : statements) {
                            List<String> problems = validateSql(entry.getKey());
                            if (!problems.isEmpty()) {
                                throw new IllegalArgumentException(
                                        String.join("; ", problems));
                            }
                            try (PreparedStatement ps =
                                         c.prepareStatement(entry.getKey())) {
                                List<Object> values =
                                        new ArrayList<>(entry.getValue().values());
                                for (int i = 0; i < values.size(); i++) {
                                    ps.setObject(i + 1, values.get(i));
                                }
                                total += ps.executeUpdate();
                            }
                        }
                        c.commit();
                        return total;
                    } catch (Exception failure) {
                        c.rollback();
                        throw failure;
                    } finally {
                        c.setAutoCommit(oldAutoCommit);
                    }
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private List<Map<String, Object>> runQuery(String sql, Map<String, Object> params)
            throws Exception {
        try (Connection c = connectionSupplier.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            List<Object> values = params == null ? List.of() : List.copyOf(params.values());
            for (int i = 0; i < values.size(); i++) {
                ps.setObject(i + 1, values.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next() && rows.size() < maxRows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static String insertSql(String table, Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String marks = String.join(", ", java.util.Collections.nCopies(row.size(), "?"));
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ")";
    }

    private static String updateSql(String table, Map<String, Object> row) {
        Object id = row.get("id");
        if (id == null) {
            throw new IllegalArgumentException("db-update requires an id value");
        }
        String assignments = String.join(", ",
                row.keySet().stream().filter(k -> !"id".equals(k))
                        .map(k -> k + " = ?").toList());
        return "UPDATE " + table + " SET " + assignments + " WHERE id = ?";
    }

    private static List<Object> bindValues(String kind, Map<String, Object> row) {
        List<Object> values = new ArrayList<>();
        if ("db-insert".equals(kind)) {
            values.addAll(row.values());
        } else if ("db-update".equals(kind)) {
            row.forEach((k, v) -> {
                if (!"id".equals(k)) {
                    values.add(v);
                }
            });
            values.add(row.get("id"));
        } else {
            values.add(row.get("id"));
        }
        return values;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
