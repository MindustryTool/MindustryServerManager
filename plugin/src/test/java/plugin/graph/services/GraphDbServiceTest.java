package plugin.graph.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDbServiceTest {

    @TempDir
    Path tempDir;

    private GraphDbService service;
    private Path dbFile;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tempDir.resolve("graph-test.sqlite");
        service = GraphDbService.forDatabase(dbFile, 10_000);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE players (id INTEGER PRIMARY KEY, name TEXT, score INTEGER)");
        }
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private <T> T join(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private Throwable failureOf(CompletableFuture<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            return null;
        } catch (Exception e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }

    @Test
    void insertQueryUpdateDeleteRoundtrip() throws Exception {
        assertEquals(1, join(service.update("db-insert", "players",
                Map.of("id", 1, "name", "Ada", "score", 10))));
        assertEquals(1, join(service.update("db-insert", "players",
                Map.of("id", 2, "name", "Bob", "score", 5))));

        List<Map<String, Object>> rows = join(service.query(
                "SELECT id, name FROM players WHERE score > ? ORDER BY id",
                Map.of("min", 6)));
        assertEquals(1, rows.size());
        assertEquals("Ada", rows.get(0).get("name"));

        assertEquals(1, join(service.update("db-update", "players",
                Map.of("id", 2, "name", "Bobby", "score", 7))));
        rows = join(service.query("SELECT name FROM players WHERE id = ?", Map.of("id", 2)));
        assertEquals("Bobby", rows.get(0).get("name"));

        assertEquals(1, join(service.update("db-delete", "players", Map.of("id", 1))));
        rows = join(service.query("SELECT id FROM players", Map.of()));
        assertEquals(1, rows.size());
    }

    @Test
    void rejectsStringLiteralsConcatenationAndComments() throws Exception {
        for (String bad : new String[] {
                "SELECT * FROM players WHERE name = 'Ada'",
                "SELECT * FROM \"players\"",
                "SELECT 1; DROP TABLE players",
                "SELECT 1 -- sneaky",
                "SELECT /* hidden */ 1",
                null,
                "   "}) {
            Throwable cause = failureOf(service.query(bad, Map.of()));
            assertInstanceOf(IllegalArgumentException.class, cause,
                    "expected rejection for: " + bad);
        }
        List<Map<String, Object>> survivors = join(service.query(
                "SELECT count(*) AS n FROM players", Map.of()));
        assertEquals(0, ((Number) survivors.get(0).get("n")).intValue(),
                "rejected statements must not have executed");
    }

    @Test
    void transactionRollsBackEverythingOnFailure() throws Exception {
        service.update("db-insert", "players",
                Map.of("id", 1, "name", "Keep", "score", 1)).get(5, TimeUnit.SECONDS);

        var batch = List.of(
                Map.entry("INSERT INTO players (id, name, score) VALUES (?, ?, ?)",
                        Map.<String, Object>of("a", 2, "b", "Temp", "c", 9)),
                Map.entry("INSERT INTO players (id, name, score) VALUES (?, ?, ?)",
                        Map.<String, Object>of("a", 3, "b", "Also-temp", "c", 8)),
                Map.entry("INSERT INTO missing_table VALUES (?)",
                        Map.<String, Object>of("x", 99)));

        try {
            join(service.transaction(batch));
            throw new AssertionError("transaction should have failed");
        } catch (Exception expected) {
            Throwable root = expected;
            while (root.getCause() != null) { root = root.getCause(); }
            assertTrue(String.valueOf(root).contains("missing_table"),
                    String.valueOf(expected));
        }

        List<Map<String, Object>> rows = join(service.query(
                "SELECT id FROM players ORDER BY id", Map.of()));
        assertEquals(1, rows.size());
        assertEquals(1, ((Number) rows.get(0).get("id")).intValue());
    }

    @Test
    void largeResultSetRunsToCompletionOffTheCallingThread() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE big (id INTEGER PRIMARY KEY, payload TEXT)");
            c.setAutoCommit(false);
            for (int i = 0; i < 50_000; i++) {
                s.addBatch("INSERT INTO big (payload) VALUES ('row-" + i + "')");
            }
            s.executeBatch();
            c.commit();
        }

        List<Map<String, Object>> rows = join(service.query(
                "SELECT id, payload FROM big", Map.of()));

        assertEquals(10_000, rows.size(), "result set must be capped at maxRows");
        assertEquals("row-0", rows.get(0).get("payload"));
        assertEquals("row-9999", rows.get(9_999).get("payload"));
    }
}
