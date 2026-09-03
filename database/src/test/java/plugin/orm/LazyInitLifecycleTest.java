package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LazyInitLifecycleTest {

    @TempDir
    Path tempDir;

    private Path dbPath() {
        return tempDir.resolve("lazy.db");
    }

    private long ormDbThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("orm-db"))
                .count();
    }

    @Test
    void buildingDatabaseCreatesNothing() {
        var db = SQLiteDatabase.builder().path(dbPath().toString()).build();

        assertFalse(db.isInitialized());
        assertEquals(0, db.initializationCount());
        assertNull(db.ownedExecutorOrNull());
        assertFalse(Files.exists(dbPath()));
        assertEquals(0, ormDbThreadCount());
        db.close();
    }

    @Test
    void firstOperationInitializesExactlyOnce() {
        var db = SQLiteDatabase.builder().path(dbPath().toString()).build();

        db.raw("CREATE TABLE t (id INTEGER)");

        assertTrue(db.isInitialized());
        assertEquals(1, db.initializationCount());
        assertTrue(Files.exists(dbPath()));
        db.close();
    }

    @Test
    void syncOperationsCreateExecutorButNoThreads() {
        var db = SQLiteDatabase.builder().path(dbPath().toString()).build();

        db.raw("CREATE TABLE t (id INTEGER)");

        assertTrue(db.ownedExecutorOrNull() != null);
        assertEquals(0, ormDbThreadCount());
        db.close();
    }

    @Test
    void closeBeforeFirstUseIsSafeNoOp() {
        var db = SQLiteDatabase.builder().path(dbPath().toString()).build();

        db.close();
        db.close();

        assertFalse(db.isInitialized());
        assertEquals(0, db.initializationCount());
        assertFalse(Files.exists(dbPath()));
    }

    @Test
    void operationsAfterCloseFailWithClearException() {
        var db = SQLiteDatabase.builder().path(dbPath().toString()).build();
        db.raw("CREATE TABLE t (id INTEGER)");
        db.close();

        var error = assertThrows(OrmException.class, () -> db.raw("SELECT 1"));
        assertTrue(error.getMessage().contains("closed"));
        assertThrows(OrmException.class, () -> db.select().from(Fixtures.USERS).fetch());
        assertThrows(OrmException.class, () -> db.transaction(tx -> {
        }));
    }

    @Test
    void closeShutsDownOwnedExecutor() {
        var db = SQLiteDatabase.builder().path(dbPath().toString()).build();
        db.rawQuery("SELECT 1");
        ExecutorService executor = db.ownedExecutorOrNull();
        assertTrue(executor != null && !executor.isShutdown());

        db.close();

        assertTrue(executor.isShutdown());
    }

    @Test
    void closeDoesNotShutDownExternalExecutor() throws Exception {
        ExecutorService external = Executors.newSingleThreadExecutor();
        try {
            var db = SQLiteDatabase.builder().path(dbPath().toString()).executor(external).build();
            db.rawQuery("SELECT 1");
            db.close();

            assertFalse(external.isShutdown());
            assertEquals(1, external.submit(() -> 1).get());
        } finally {
            external.shutdownNow();
        }
    }

    @Test
    void externalExecutorTakesOverAsyncWork() {
        ExecutorService external = Executors.newSingleThreadExecutor(r -> new Thread(r, "custom-db-exec"));
        try {
            var db = SQLiteDatabase.builder().path(dbPath().toString()).executor(external).build();
            int value = db.rawQuery("SELECT 1 AS one").get(0).getInt("one");
            assertEquals(1, value);
            assertNull(db.ownedExecutorOrNull());
        } finally {
            external.shutdownNow();
        }
    }

    @Test
    void sqlErrorDoesNotLeaveDatabaseBroken() {
        var db = SQLiteDatabase.builder().path(dbPath().toString()).build();

        assertThrows(OrmException.class, () -> db.raw("SELECT FROM nope"));

        db.raw("CREATE TABLE t (id INTEGER)");
        db.raw("INSERT INTO t (id) VALUES (1)");
        assertEquals(1, db.rawQuery("SELECT id FROM t").size());
        db.close();
    }

    @Test
    void asyncOperationAfterCloseCompletesExceptionally() {
        var db = SQLiteDatabase.builder().path(dbPath().toString()).build();
        db.close();

        var future = db.select().from(Fixtures.USERS).fetchAsync();
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void repeatedOpenCloseCyclesLeakNoFileHandles() throws Exception {
        for (int i = 0; i < 5; i++) {
            Path path = tempDir.resolve("cycle" + i + ".db");
            var db = SQLiteDatabase.builder().path(path.toString()).build();
            db.raw("CREATE TABLE t (id INTEGER)");
            db.raw("INSERT INTO t (id) VALUES (1)");
            db.close();
            assertTrue(Files.deleteIfExists(path), "db file still locked after close (leaked connection)");
        }
    }
}
