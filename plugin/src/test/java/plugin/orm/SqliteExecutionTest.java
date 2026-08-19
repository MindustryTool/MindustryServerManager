package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SqliteExecutionTest {

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

    private void insertUser(long id, String name, boolean active) {
        test.db.insert(Fixtures.USERS)
                .set(Fixtures.USERS_ID, id)
                .set(Fixtures.USERS_NAME, name)
                .set(Fixtures.USERS_ACTIVE, active)
                .execute();
    }

    @Test
    void insertThenSelectReturnsInsertedRows() {
        insertUser(1, "Hau", true);
        insertUser(2, "Bob", false);

        var rows = test.db.select(Fixtures.USERS_ID, Fixtures.USERS_NAME, Fixtures.USERS_ACTIVE)
                .from(Fixtures.USERS)
                .orderBy(Fixtures.USERS_ID.asc())
                .fetch();

        assertEquals(2, rows.size());
        assertEquals(1L, rows.get(0).getLong("id"));
        assertEquals("Hau", rows.get(0).getString("name"));
        assertTrue(rows.get(0).getBoolean("active"));
        assertEquals(2L, rows.get(1).getLong("id"));
        assertEquals("Bob", rows.get(1).getString("name"));
    }

    @Test
    void insertReturnsAffectedRowCount() {
        int count = test.db.insert(Fixtures.USERS)
                .set(Fixtures.USERS_NAME, "Solo")
                .execute();
        assertEquals(1, count);
    }

    @Test
    void selectWithWhereFiltersRows() {
        insertUser(1, "A", true);
        insertUser(2, "B", false);
        insertUser(3, "C", true);

        var rows = test.db.select(Fixtures.USERS_NAME).from(Fixtures.USERS)
                .where(Fixtures.USERS_ACTIVE.eq(true))
                .orderBy(Fixtures.USERS_ID.asc())
                .fetch();

        assertEquals(List.of("A", "C"), rows.stream().map(r -> r.getString("name")).toList());
    }

    @Test
    void selectWithInCondition() {
        insertUser(1, "A", true);
        insertUser(2, "B", true);
        insertUser(3, "C", true);

        var rows = test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS)
                .where(Fixtures.USERS_ID.in(1L, 3L))
                .orderBy(Fixtures.USERS_ID.asc())
                .fetch();

        assertEquals(List.of(1L, 3L), rows.stream().map(r -> r.getLong("id")).toList());
    }

    @Test
    void selectWithIsNullCondition() {
        insertUser(1, "A", true);
        test.db.insert(Fixtures.USERS).set(Fixtures.USERS_ID, 2L).set(Fixtures.USERS_ACTIVE, true).execute();

        var rows = test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS)
                .where(Fixtures.USERS_NAME.isNull())
                .fetch();

        assertEquals(1, rows.size());
        assertEquals(2L, rows.get(0).getLong("id"));
    }

    @Test
    void selectWithLikeCondition() {
        insertUser(1, "Hau", true);
        insertUser(2, "Hanna", true);
        insertUser(3, "Bob", true);

        var rows = test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS)
                .where(Fixtures.USERS_NAME.like("Ha%"))
                .orderBy(Fixtures.USERS_ID.asc())
                .fetch();

        assertEquals(List.of(1L, 2L), rows.stream().map(r -> r.getLong("id")).toList());
    }

    @Test
    void selectWithOrderByDesc() {
        insertUser(1, "A", true);
        insertUser(2, "B", true);
        insertUser(3, "C", true);

        var rows = test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS)
                .orderBy(Fixtures.USERS_ID.desc())
                .fetch();

        assertEquals(List.of(3L, 2L, 1L), rows.stream().map(r -> r.getLong("id")).toList());
    }

    @Test
    void selectWithLimitAndOffset() {
        for (long i = 1; i <= 5; i++) {
            insertUser(i, "U" + i, true);
        }

        var rows = test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS)
                .orderBy(Fixtures.USERS_ID.asc())
                .limit(2)
                .offset(1)
                .fetch();

        assertEquals(List.of(2L, 3L), rows.stream().map(r -> r.getLong("id")).toList());
    }

    @Test
    void joinReturnsCombinedColumns() {
        test.db.insert(Fixtures.SERVERS).set(Fixtures.SERVERS_ID, 10L).set(Fixtures.SERVERS_NAME, "Main").execute();
        test.db.insert(Fixtures.SERVERS).set(Fixtures.SERVERS_ID, 20L).set(Fixtures.SERVERS_NAME, "Backup").execute();
        insertUser(1, "Hau", true);
        test.db.update(Fixtures.USERS).set(Fixtures.USERS_SERVER_ID, 10L).where(Fixtures.USERS_ID.eq(1L)).execute();

        var rows = test.db.select(Fixtures.USERS_NAME, Fixtures.SERVERS_NAME)
                .from(Fixtures.USERS)
                .join(Fixtures.SERVERS)
                .on(Fixtures.USERS_SERVER_ID.eqColumn(Fixtures.SERVERS_ID))
                .where(Fixtures.USERS_ACTIVE.eq(true))
                .fetch();

        assertEquals(1, rows.size());
        assertEquals("Hau", rows.get(0).get(Fixtures.USERS_NAME));
        assertEquals("Main", rows.get(0).get(Fixtures.SERVERS_NAME));
        assertEquals("Hau", rows.get(0).getString("users.name"));
        assertEquals("Main", rows.get(0).getString("servers.name"));
    }

    @Test
    void upsertInsertsThenUpdates() {
        test.db.insert(Fixtures.SESSIONS)
                .set(Fixtures.SESSIONS_UUID, "u1")
                .set(Fixtures.SESSIONS_DATA, "{}")
                .set(Fixtures.SESSIONS_TOTAL_EXP, 5L)
                .onConflictDoUpdate(Fixtures.SESSIONS_UUID, Fixtures.SESSIONS_DATA, Fixtures.SESSIONS_TOTAL_EXP)
                .execute();

        test.db.insert(Fixtures.SESSIONS)
                .set(Fixtures.SESSIONS_UUID, "u1")
                .set(Fixtures.SESSIONS_DATA, "{\"x\":1}")
                .set(Fixtures.SESSIONS_TOTAL_EXP, 9L)
                .onConflictDoUpdate(Fixtures.SESSIONS_UUID, Fixtures.SESSIONS_DATA, Fixtures.SESSIONS_TOTAL_EXP)
                .execute();

        var rows = test.db.select().from(Fixtures.SESSIONS).fetch();
        assertEquals(1, rows.size());
        assertEquals("{\"x\":1}", rows.get(0).getString("data"));
        assertEquals(9L, rows.get(0).getLong("totalExp"));
    }

    @Test
    void updateAffectsMatchingRowsAndReturnsCount() {
        insertUser(1, "A", true);
        insertUser(2, "B", true);
        insertUser(3, "C", true);

        int updated = test.db.update(Fixtures.USERS)
                .set(Fixtures.USERS_ACTIVE, false)
                .where(Fixtures.USERS_ID.gt(1L))
                .execute();

        assertEquals(2, updated);

        var active = test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS)
                .where(Fixtures.USERS_ACTIVE.eq(true))
                .fetch();
        assertEquals(List.of(1L), active.stream().map(r -> r.getLong("id")).toList());
    }

    @Test
    void updateAffectingZeroRowsReturnsZero() {
        insertUser(1, "A", true);

        int updated = test.db.update(Fixtures.USERS)
                .set(Fixtures.USERS_ACTIVE, false)
                .where(Fixtures.USERS_ID.eq(999L))
                .execute();

        assertEquals(0, updated);
    }

    @Test
    void deleteWithWhereRemovesOnlyMatchingRows() {
        insertUser(1, "A", true);
        insertUser(2, "B", true);
        insertUser(3, "C", true);

        int deleted = test.db.delete(Fixtures.USERS).where(Fixtures.USERS_ID.lte(2L)).execute();
        assertEquals(2, deleted);

        var remaining = test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS).fetch();
        assertEquals(List.of(3L), remaining.stream().map(r -> r.getLong("id")).toList());
    }

    @Test
    void deleteAllRemovesEverything() {
        insertUser(1, "A", true);
        insertUser(2, "B", true);

        int deleted = test.db.delete(Fixtures.USERS).all().execute();
        assertEquals(2, deleted);

        assertTrue(test.db.select().from(Fixtures.USERS).fetch().isEmpty());
    }

    @Test
    void fetchOneReturnsEmptyForNoMatch() {
        assertTrue(test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS)
                .where(Fixtures.USERS_ID.eq(1L)).fetchOne().isEmpty());
    }

    @Test
    void fetchOneReturnsValueForSingleMatch() {
        insertUser(1, "A", true);

        var row = test.db.select(Fixtures.USERS_NAME).from(Fixtures.USERS)
                .where(Fixtures.USERS_ID.eq(1L)).fetchOne();

        assertTrue(row.isPresent());
        assertEquals("A", row.get().getString("name"));
    }

    @Test
    void fetchOneFailsForMultipleMatches() {
        insertUser(1, "A", true);
        insertUser(2, "B", true);

        assertThrows(OrmException.class, () -> test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS).fetchOne());
    }

    @Test
    void syntaxErrorThrowsOrmException() {
        assertThrows(OrmException.class, () -> test.db.raw("SELECT FROM WHERE"));
    }

    @Test
    void rawValuesAreBoundAsParameters() {
        test.db.raw("INSERT INTO users (name) VALUES (?)", "raw; DROP TABLE users");
        assertEquals(1, test.db.select().from(Fixtures.USERS).fetch().size());
        assertEquals("raw; DROP TABLE users",
                test.db.rawQuery("SELECT name FROM users WHERE name = ?", "raw; DROP TABLE users")
                        .get(0).getString("name"));
    }

    @Test
    void rawExecuteReturnsRowsForQueriesAndCountsForUpdates() {
        insertUser(1, "A", true);

        var result = test.db.rawExecute("SELECT name FROM users");
        assertTrue(result.hasRows());
        assertEquals("A", result.rows().get(0).getString("name"));

        var update = test.db.rawExecute("UPDATE users SET active = 0");
        assertEquals(1, update.updateCount());
    }

    @Test
    void hasColumnDetectsExistingAndMissingColumns() {
        assertTrue(test.db.hasColumn("users", "name"));
        assertTrue(!test.db.hasColumn("users", "nope"));
        assertTrue(!test.db.hasColumn("missing_table", "anything"));
    }

    @Test
    void offsetWithoutLimitWorks() {
        for (long i = 1; i <= 3; i++) {
            insertUser(i, "U" + i, true);
        }

        var rows = test.db.select(Fixtures.USERS_ID).from(Fixtures.USERS)
                .orderBy(Fixtures.USERS_ID.asc())
                .offset(2)
                .fetch();

        assertEquals(List.of(3L), rows.stream().map(r -> r.getLong("id")).toList());
    }
}
