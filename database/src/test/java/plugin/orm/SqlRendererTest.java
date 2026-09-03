package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import plugin.orm.sql.SqlRenderer;
import plugin.orm.sql.TableQuery;
import plugin.orm.table.Table;

public class SqlRendererTest {

    private final SQLiteDatabase db = SQLiteDatabase.builder().path("render-only.db").build();

    @Test
    void rendersSimpleSelect() {
        var q = db.select(Fixtures.USERS_ID).from(Fixtures.USERS).toSqlQuery();
        assertEquals("SELECT users.id FROM users", Render.normalize(q.sql()));
        assertTrue(q.parameters().isEmpty());
    }

    @Test
    void rendersSelectWithMultipleColumns() {
        var q = db.select(Fixtures.USERS_ID, Fixtures.USERS_NAME).from(Fixtures.USERS).toSqlQuery();
        assertEquals("SELECT users.id, users.name FROM users", Render.normalize(q.sql()));
    }

    @Test
    void rendersStarWhenNoColumnsSelected() {
        var q = db.select().from(Fixtures.USERS).toSqlQuery();
        assertEquals("SELECT * FROM users", Render.normalize(q.sql()));
    }

    @Test
    void rendersSelectWithWhere() {
        var q = db.select(Fixtures.USERS_ID).from(Fixtures.USERS)
                .where(Fixtures.USERS_ACTIVE.eq(true)).toSqlQuery();
        assertEquals("SELECT users.id FROM users WHERE users.active = ?", Render.normalize(q.sql()));
        assertEquals(List.of(true), q.parameters());
    }

    @Test
    void bindsWhereParametersInOrder() {
        var q = db.select().from(Fixtures.USERS)
                .where(Fixtures.USERS_ACTIVE.eq(true).and(Fixtures.USERS_ID.gt(10L))).toSqlQuery();
        assertEquals("SELECT * FROM users WHERE users.active = ? AND users.id > ?", Render.normalize(q.sql()));
        assertEquals(List.of(true, 10L), q.parameters());
    }

    @Test
    void rendersNestedGroupingWithParens() {
        var q = db.select().from(Fixtures.USERS)
                .where(Fixtures.USERS_ACTIVE.eq(true)
                        .and(Fixtures.USERS_ID.gt(10L).or(Fixtures.USERS_NAME.isNull())))
                .toSqlQuery();
        assertEquals("SELECT * FROM users WHERE users.active = ? AND (users.id > ? OR users.name IS NULL)",
                Render.normalize(q.sql()));
        assertEquals(List.of(true, 10L), q.parameters());
    }

    @Test
    void rendersInAndNotInInWhere() {
        var q = db.select().from(Fixtures.USERS)
                .where(Fixtures.USERS_ID.in(1L, 2L).and(Fixtures.USERS_ACTIVE.notIn(true))).toSqlQuery();
        assertEquals("SELECT * FROM users WHERE users.id IN (?, ?) AND users.active NOT IN (?)",
                Render.normalize(q.sql()));
        assertEquals(List.of(1L, 2L, true), q.parameters());
    }

    @Test
    void rendersLikeInWhere() {
        var q = db.select().from(Fixtures.USERS)
                .where(Fixtures.USERS_NAME.like("H%")).toSqlQuery();
        assertEquals("SELECT * FROM users WHERE users.name LIKE ?", Render.normalize(q.sql()));
        assertEquals(List.of("H%"), q.parameters());
    }

    @Test
    void rendersIsNullAndIsNotNull() {
        var q = db.select().from(Fixtures.USERS)
                .where(Fixtures.USERS_NAME.isNull().and(Fixtures.USERS_NAME.isNotNull().not())).toSqlQuery();
        assertEquals("SELECT * FROM users WHERE users.name IS NULL AND (NOT (users.name IS NOT NULL))",
                Render.normalize(q.sql()));
    }

    @Test
    void rendersOrderByAscAndDesc() {
        var q = db.select().from(Fixtures.USERS)
                .orderBy(Fixtures.USERS_ACTIVE.desc(), Fixtures.USERS_ID.asc()).toSqlQuery();
        assertEquals("SELECT * FROM users ORDER BY users.active DESC, users.id ASC", Render.normalize(q.sql()));
    }

    @Test
    void rendersLimitAsParameter() {
        var q = db.select().from(Fixtures.USERS).limit(20).toSqlQuery();
        assertEquals("SELECT * FROM users LIMIT ?", Render.normalize(q.sql()));
        assertEquals(List.of(20), q.parameters());
    }

    @Test
    void rendersOffsetAsParameter() {
        var q = db.select().from(Fixtures.USERS).limit(20).offset(40).toSqlQuery();
        assertEquals("SELECT * FROM users LIMIT ? OFFSET ?", Render.normalize(q.sql()));
        assertEquals(List.of(20, 40), q.parameters());
    }

    @Test
    void rendersOffsetWithoutLimitWithMinusOneLimit() {
        var q = db.select().from(Fixtures.USERS).offset(40).toSqlQuery();
        assertEquals("SELECT * FROM users LIMIT -1 OFFSET ?", Render.normalize(q.sql()));
        assertEquals(List.of(40), q.parameters());
    }

    @Test
    void rendersJoinWithOn() {
        var q = db.select(Fixtures.USERS_NAME, Fixtures.SERVERS_NAME)
                .from(Fixtures.USERS)
                .join(Fixtures.SERVERS)
                .on(Fixtures.USERS_SERVER_ID.eqColumn(Fixtures.SERVERS_ID))
                .toSqlQuery();
        assertEquals(
                "SELECT users.name, servers.name FROM users INNER JOIN servers ON users.server_id = servers.id",
                Render.normalize(q.sql()));
    }

    @Test
    void rendersJoinWithWhereOrderAndLimit() {
        var q = db.select(Fixtures.USERS_NAME)
                .from(Fixtures.USERS)
                .join(Fixtures.SERVERS)
                .on(Fixtures.USERS_SERVER_ID.eqColumn(Fixtures.SERVERS_ID))
                .where(Fixtures.USERS_ACTIVE.eq(true))
                .orderBy(Fixtures.USERS_ID.desc())
                .limit(5)
                .toSqlQuery();
        assertEquals(
                "SELECT users.name FROM users INNER JOIN servers ON users.server_id = servers.id "
                        + "WHERE users.active = ? ORDER BY users.id DESC LIMIT ?",
                Render.normalize(q.sql()));
        assertEquals(List.of(true, 5), q.parameters());
    }

    @Test
    void rendersInsertWithColumnsAndValues() {
        var q = db.insert(Fixtures.USERS)
                .set(Fixtures.USERS_NAME, "Hau")
                .set(Fixtures.USERS_ACTIVE, true)
                .toSqlQuery();
        assertEquals("INSERT INTO users (name, active) VALUES (?, ?)", Render.normalize(q.sql()));
        assertEquals(List.of("Hau", true), q.parameters());
    }

    @Test
    void rendersInsertUpsert() {
        var q = db.insert(Fixtures.SESSIONS)
                .set(Fixtures.SESSIONS_UUID, "abc")
                .set(Fixtures.SESSIONS_DATA, "{}")
                .set(Fixtures.SESSIONS_TOTAL_EXP, 0L)
                .onConflictDoUpdate(Fixtures.SESSIONS_UUID, Fixtures.SESSIONS_DATA, Fixtures.SESSIONS_TOTAL_EXP)
                .toSqlQuery();
        assertEquals(
                "INSERT INTO sessions (uuid, data, totalExp) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET data = excluded.data, totalExp = excluded.totalExp",
                Render.normalize(q.sql()));
        assertEquals(List.of("abc", "{}", 0L), q.parameters());
    }

    @Test
    void rendersUpdateWithWhere() {
        var q = db.update(Fixtures.USERS)
                .set(Fixtures.USERS_NAME, "Hau")
                .set(Fixtures.USERS_ACTIVE, false)
                .where(Fixtures.USERS_ID.eq(123L))
                .toSqlQuery();
        assertEquals("UPDATE users SET name = ?, active = ? WHERE users.id = ?",
                Render.normalize(q.sql()));
        assertEquals(List.of("Hau", false, 123L), q.parameters());
    }

    @Test
    void rendersUpdateWithoutWhere() {
        var q = db.update(Fixtures.USERS).set(Fixtures.USERS_NAME, "x").toSqlQuery();
        assertEquals("UPDATE users SET name = ?", Render.normalize(q.sql()));
        assertEquals(List.of("x"), q.parameters());
    }

    @Test
    void rendersDeleteWithWhere() {
        var q = db.delete(Fixtures.USERS).where(Fixtures.USERS_ID.eq(123L)).toSqlQuery();
        assertEquals("DELETE FROM users WHERE users.id = ?", Render.normalize(q.sql()));
        assertEquals(List.of(123L), q.parameters());
    }

    @Test
    void rendersDeleteAll() {
        var q = db.delete(Fixtures.USERS).all().toSqlQuery();
        assertEquals("DELETE FROM users", Render.normalize(q.sql()));
        assertTrue(q.parameters().isEmpty());
    }

    @Test
    void deleteWithoutWhereOrAllFails() {
        assertThrows(OrmException.class, () -> db.delete(Fixtures.USERS).toSqlQuery());
        assertThrows(OrmException.class, () -> db.delete(Fixtures.USERS).execute());
    }

    @Test
    void selectWithoutFromFails() {
        assertThrows(OrmException.class, () -> db.select(Fixtures.USERS_ID).toSqlQuery());
    }

    @Test
    void insertWithoutColumnsFails() {
        assertThrows(OrmException.class, () -> db.insert(Fixtures.USERS).toSqlQuery());
    }

    @Test
    void updateWithoutColumnsFails() {
        assertThrows(OrmException.class, () -> db.update(Fixtures.USERS).toSqlQuery());
    }

    @Test
    void onWithoutJoinFails() {
        assertThrows(OrmException.class, () -> db.select().from(Fixtures.USERS).on(Fixtures.USERS_ID.eq(1L)));
    }

    @Test
    void rendersSessionsSchema() {
        var table = new TableQuery(Fixtures.SESSIONS, List.of(
                Fixtures.SESSIONS_UUID.primaryKey(),
                Fixtures.SESSIONS_DATA.notNull(),
                Fixtures.SESSIONS_TOTAL_EXP.defaultValue(0L)));

        var q = SqlRenderer.renderTable(table);

        assertEquals(
                "CREATE TABLE IF NOT EXISTS sessions (uuid TEXT PRIMARY KEY, data TEXT NOT NULL, totalExp INTEGER DEFAULT 0)",
                Render.normalize(q.sql()));
        assertTrue(q.parameters().isEmpty());
    }

    @Test
    void rendersPlayerLoginsSchema() {
        var table = new TableQuery(Fixtures.PLAYER_LOGINS, List.of(
                Fixtures.PLAYER_LOGINS_UUID.primaryKey(),
                Fixtures.PLAYER_LOGINS_DATE.notNull()));

        var q = SqlRenderer.renderTable(table);

        assertEquals(
                "CREATE TABLE IF NOT EXISTS player_logins (uuid TEXT PRIMARY KEY, last_login_date TEXT NOT NULL)",
                Render.normalize(q.sql()));
        assertTrue(q.parameters().isEmpty());
    }

    @Test
    void rendersEveryConstraintFlagCombination() {
        var table = new TableQuery(Fixtures.USERS, List.of(
                Fixtures.USERS_ID.primaryKey(),
                Fixtures.USERS_NAME.notNull(),
                Fixtures.USERS_ACTIVE.primaryKey().notNull()));

        var q = SqlRenderer.renderTable(table);

        assertEquals(
                "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, active INTEGER PRIMARY KEY NOT NULL)",
                Render.normalize(q.sql()));
    }

    @Test
    void rendersNumericLiteralDefaults() {
        var users = Table.of("users");
        var table = new TableQuery(users, List.of(
                users.column("totalExp", Long.class).defaultValue(0L),
                users.column("active", Boolean.class).defaultValue(true),
                users.column("score", Float.class).defaultValue(1.5f)));

        var q = SqlRenderer.renderTable(table);

        assertEquals(
                "CREATE TABLE IF NOT EXISTS users (totalExp INTEGER DEFAULT 0, active INTEGER DEFAULT 1, score REAL DEFAULT 1.5)",
                Render.normalize(q.sql()));
    }

    @Test
    void rendersTextDefaultQuotedAndEscaped() {
        var table = new TableQuery(Fixtures.USERS, List.of(
                Fixtures.USERS_NAME.defaultValue("new")));

        var q = SqlRenderer.renderTable(table);

        assertEquals("CREATE TABLE IF NOT EXISTS users (name TEXT DEFAULT 'new')", Render.normalize(q.sql()));
    }

    @Test
    void omitsDefaultClauseWhenNoDefaultSet() {
        var table = new TableQuery(Fixtures.USERS, List.of(
                Fixtures.USERS_NAME.primaryKey()));

        var q = SqlRenderer.renderTable(table);

        assertEquals("CREATE TABLE IF NOT EXISTS users (name TEXT PRIMARY KEY)", Render.normalize(q.sql()));
    }

    @Test
    void rejectsArrayDefault() {
        var users = Table.of("users");
        var table = new TableQuery(users, List.of(
                users.column("blob", byte[].class).defaultValue(new byte[] { 1 })));

        assertThrows(OrmException.class, () -> SqlRenderer.renderTable(table));
    }

    @Test
    void renderTableWithoutColumnsFails() {
        assertThrows(OrmException.class, () -> SqlRenderer.renderTable(new TableQuery(Fixtures.USERS, List.of())));
    }
}
