package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AsyncExecutionTest {

    @TempDir
    Path tempDir;

    TestDatabase test;

    @BeforeEach
    void setUp() {
        test = TestDatabase.create(tempDir);
    }

    @AfterEach
    void tearDown() {
        test.close();
    }

    @Test
    void asyncInsertIsVisibleToLaterQueries() throws Exception {
        test.db.insert(Fixtures.USERS).set(Fixtures.USERS_NAME, "async").executeAsync().join();

        var row = test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS).fetchOne();
        assertTrue(row.isPresent());
    }

    @Test
    void asyncWorkRunsOnProvidedExecutorThread() throws Exception {
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        ExecutorService external = Executors.newFixedThreadPool(2, (ThreadFactory) runnable -> new Thread(runnable, "test-exec"));
        try {
            var db = SQLiteDatabase.builder().path(tempDir.resolve("exec.db").toString()).executor(external).build();
            db.raw("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int n = i;
                futures.add(db.select().from(Fixtures.USERS).fetchAsync()
                        .thenAccept(rows -> threadNames.add(Thread.currentThread().getName() + n)));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            assertFalse(threadNames.isEmpty());
            assertTrue(threadNames.stream().allMatch(name -> name.startsWith("test-exec")));
        } finally {
            external.shutdownNow();
        }
    }

    @Test
    void asyncFailurePropagatesToFuture() {
        var db = SQLiteDatabase.builder().path(tempDir.resolve("missing.db").toString()).build();
        var future = db.select().from(Fixtures.USERS).fetchAsync();

        var error = assertThrows(CompletionException.class, future::join);
        assertTrue(error.getCause() instanceof OrmException);
        db.close();
    }

    @Test
    void asyncTransactionCommitsOnSuccess() throws Exception {
        test.db.transactionAsync(tx -> tx.insert(Fixtures.USERS)
                .set(Fixtures.USERS_NAME, "tx").execute()).join();

        assertEquals(1, test.db.select().from(Fixtures.USERS).fetch().size());
    }

    @Test
    void asyncTransactionRollsBackOnFailure() {
        var future = test.db.transactionAsync(tx -> {
            tx.insert(Fixtures.USERS).set(Fixtures.USERS_NAME, "tx").execute();
            throw new IllegalStateException("boom");
        });

        var error = assertThrows(CompletionException.class, future::join);
        assertTrue(error.getCause() instanceof IllegalStateException);
        assertTrue(test.db.select().from(Fixtures.USERS).fetch().isEmpty());
    }

    @Test
    void concurrentFirstUseInitializesExactlyOnce() throws Exception {
        Path path = tempDir.resolve("race.db");
        var db = SQLiteDatabase.builder().path(path.toString()).build();

        int workers = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return db.rawQuery("SELECT 1 AS one").size();
            }, pool));
        }
        start.countDown();

        for (var future : futures) {
            assertEquals(1, future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertEquals(1, db.initializationCount());
        db.close();
    }

    @Test
    void asyncOperationsCanOverlapSafely() throws Exception {
        int total = 40;
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int n = i;
            futures.add(test.db.insert(Fixtures.USERS).set(Fixtures.USERS_NAME, "u" + n).executeAsync());
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        assertEquals(total, test.db.select().from(Fixtures.USERS).fetch().size());
    }
}
