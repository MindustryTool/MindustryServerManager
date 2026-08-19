package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LazyConnectionTest {

    @TempDir
    Path tempDir;

    @Test
    void buildingDatabaseCreatesNoConnection() {
        Path path = tempDir.resolve("conn.db");
        var db = SQLiteDatabase.builder().path(path.toString()).build();

        assertFalse(db.isInitialized());
        assertFalse(Files.exists(path));
        db.close();
    }

    @Test
    void firstOperationCreatesConnectionAndFile() {
        Path path = tempDir.resolve("conn.db");
        var db = SQLiteDatabase.builder().path(path.toString()).build();

        db.raw("CREATE TABLE t (id INTEGER)");

        assertTrue(db.isInitialized());
        assertTrue(Files.exists(path));
        db.close();
    }

    @Test
    void concurrentSyncOperationsEachSucceed() throws Exception {
        Path path = tempDir.resolve("concurrent.db");
        var db = SQLiteDatabase.builder().path(path.toString()).build();
        db.raw("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");

        int threads = 16;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    int count = 0;
                    for (int i = 0; i < perThread; i++) {
                        db.insert(Fixtures.USERS)
                                .set(Fixtures.USERS_NAME, "t" + threadId + "-" + i)
                                .execute();
                        count++;
                    }
                    return count;
                }, pool));
            }
            for (var future : futures) {
                assertEquals(perThread, future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, db.rawQuery("SELECT COUNT(*) AS c FROM users").get(0).getInt("c"));
        db.close();
    }

    @Test
    void concurrentReadsAndWritesSucceed() throws Exception {
        Path path = tempDir.resolve("rw.db");
        var db = SQLiteDatabase.builder().path(path.toString()).build();
        db.raw("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
        db.raw("INSERT INTO users (name) VALUES ('seed')");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int idx = i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    db.insert(Fixtures.USERS).set(Fixtures.USERS_NAME, "w" + idx).execute();
                    return db.select(Fixtures.USERS_ID).from(Fixtures.USERS).fetch().size();
                }, pool));
            }
            for (var future : futures) {
                assertTrue(future.get() >= 1);
            }
        } finally {
            pool.shutdownNow();
        }
        db.close();
    }

    @Test
    void connectionPerOperationMeansFileUnlockedBetweenOps() throws Exception {
        Path path = tempDir.resolve("unlock.db");
        var db = SQLiteDatabase.builder().path(path.toString()).build();

        for (int i = 0; i < 3; i++) {
            db.raw("CREATE TABLE IF NOT EXISTS t (id INTEGER)");
            db.raw("INSERT INTO t (id) VALUES (1)");
        }
        assertEquals(3, db.rawQuery("SELECT COUNT(*) AS c FROM t").get(0).getInt("c"));
        db.close();
        assertTrue(Files.deleteIfExists(path));
    }
}
