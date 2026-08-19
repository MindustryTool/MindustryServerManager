package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TransactionTest {

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
    void commitOnSuccessPersistsChanges() {
        test.db.transaction(tx -> {
            tx.insert(Fixtures.USERS).set(Fixtures.USERS_ID, 1L).set(Fixtures.USERS_NAME, "A").execute();
            tx.update(Fixtures.USERS).set(Fixtures.USERS_NAME, "B").where(Fixtures.USERS_ID.eq(1L)).execute();
        });

        var row = test.db.select(Fixtures.USERS_NAME).from(Fixtures.USERS).fetchOne();
        assertTrue(row.isPresent());
        assertEquals("B", row.get().getString("name"));
    }

    @Test
    void rollbackOnExceptionUndoesChanges() {
        assertThrows(IllegalStateException.class, () -> test.db.transaction(tx -> {
            tx.insert(Fixtures.USERS).set(Fixtures.USERS_ID, 1L).set(Fixtures.USERS_NAME, "A").execute();
            throw new IllegalStateException("boom");
        }));

        assertTrue(test.db.select().from(Fixtures.USERS).fetch().isEmpty());
    }

    @Test
    void transactionSeesOwnWrites() {
        test.db.transaction(tx -> {
            tx.insert(Fixtures.USERS).set(Fixtures.USERS_ID, 1L).set(Fixtures.USERS_NAME, "A").execute();
            var rows = tx.select(Fixtures.USERS_ID).from(Fixtures.USERS).fetch();
            assertEquals(1, rows.size());
        });
    }

    @Test
    void failedTransactionRestoresConnectionStateForLaterOps() {
        assertThrows(RuntimeException.class, () -> test.db.transaction(tx -> {
            tx.insert(Fixtures.USERS).set(Fixtures.USERS_ID, 1L).set(Fixtures.USERS_NAME, "A").execute();
            throw new RuntimeException("boom");
        }));

        test.db.insert(Fixtures.USERS).set(Fixtures.USERS_ID, 2L).set(Fixtures.USERS_NAME, "B").execute();
        assertEquals(1, test.db.select().from(Fixtures.USERS).fetch().size());
    }

    @Test
    void failedTransactionWithSqlErrorRollsBack() {
        assertThrows(OrmException.class, () -> test.db.transaction(tx -> {
            tx.insert(Fixtures.USERS).set(Fixtures.USERS_ID, 1L).set(Fixtures.USERS_NAME, "A").execute();
            tx.insert(Fixtures.SESSIONS).set(Fixtures.SESSIONS_UUID, "u").execute();
        }));

        assertTrue(test.db.select().from(Fixtures.USERS).fetch().isEmpty());
    }

    @Test
    void nestedTransactionIsRejected() {
        assertThrows(OrmException.class, () -> test.db.transaction(tx ->
                test.db.transaction(inner -> inner.insert(Fixtures.USERS)
                        .set(Fixtures.USERS_ID, 1L).execute())));
    }

    @Test
    void transactionRollbackAfterPartialCommitAttempt() {
        test.db.transaction(tx -> {
            tx.insert(Fixtures.USERS).set(Fixtures.USERS_ID, 1L).set(Fixtures.USERS_NAME, "A").execute();
            tx.insert(Fixtures.USERS).set(Fixtures.USERS_ID, 2L).set(Fixtures.USERS_NAME, "B").execute();
        });

        assertEquals(2, test.db.select().from(Fixtures.USERS).fetch().size());
    }
}
