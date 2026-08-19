package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import plugin.database.schema.PlayerLogins;
import plugin.database.schema.Sessions;
import plugin.orm.table.Column;
import plugin.orm.table.Table;

public class TableCreationTest {

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

    private Map<String, Row> tableInfo(String table) {
        return test.db.rawQuery("PRAGMA table_info(" + table + ")").stream()
                .collect(Collectors.toMap(row -> row.getString("name"), Function.identity()));
    }

    @Test
    void tableTracksColumnsOnColumnCall() {
        var table = Table.of("tracked");

        assertEquals(0, table.columns().size());
        Column<String> uuid = table.column("uuid", String.class);
        Column<Long> exp = table.column("totalExp", Long.class);

        assertEquals(2, table.columns().size());
        assertTrue(table.columns().contains(uuid));
        assertTrue(table.columns().contains(exp));

        assertThrows(OrmException.class, () -> table.column("uuid", String.class));
        assertEquals(2, table.columns().size());
    }

    @Test
    void tableTracksMetadataFromColumnBuilders() {
        var table = Table.of("tracked");
        table.column("uuid", String.class).primaryKey();
        table.column("data", String.class).notNull();
        table.column("totalExp", Long.class).defaultValue(0L);

        var columns = table.columns();
        assertEquals(3, columns.size());
        assertTrue(columns.get(0).isPrimaryKey());
        assertTrue(columns.get(1).isNotNullConstraint());
        assertEquals(0L, columns.get(2).defaultValueOrNull());
    }

    @Test
    void createsSessionsSchemaWithConstraints() {
        test.db.raw("DROP TABLE sessions");
        test.db.createTableIfNotExists(Sessions.TABLE);

        Map<String, Row> info = tableInfo("sessions");

        assertEquals(3, info.size());
        assertEquals(1, info.get("uuid").getInt("pk"));
        assertEquals("TEXT", info.get("uuid").getString("type"));
        assertEquals(0, info.get("uuid").getInt("notnull"));
        assertEquals("TEXT", info.get("data").getString("type"));
        assertEquals(1, info.get("data").getInt("notnull"));
        assertEquals("INTEGER", info.get("totalExp").getString("type"));
        assertEquals("0", info.get("totalExp").getString("dflt_value"));
    }

    @Test
    void createsPlayerLoginsSchema() {
        test.db.raw("DROP TABLE player_logins");
        test.db.createTableIfNotExists(PlayerLogins.TABLE);

        Map<String, Row> info = tableInfo("player_logins");

        assertEquals(2, info.size());
        assertEquals(1, info.get("uuid").getInt("pk"));
        assertEquals("TEXT", info.get("uuid").getString("type"));
        assertEquals(1, info.get("last_login_date").getInt("notnull"));
    }

    @Test
    void createIsIdempotentAndPreservesData() {
        test.db.raw("DROP TABLE sessions");
        test.db.createTableIfNotExists(Sessions.TABLE);
        test.db.insert(Sessions.TABLE).set(Sessions.UUID, "u1").set(Sessions.DATA, "{}").execute();

        test.db.createTableIfNotExists(Sessions.TABLE);

        assertEquals(1, test.db.select().from(Sessions.TABLE).fetch().size());
    }

    @Test
    void addColumnIfMissingAddsOnlyWhenAbsent() {
        var legacy = Table.of("legacy");
        var totalExp = legacy.column("totalExp", Long.class).defaultValue(0L);
        test.db.raw("CREATE TABLE legacy (uuid TEXT PRIMARY KEY, data TEXT NOT NULL)");

        test.db.addColumnIfMissing(legacy, totalExp);

        Map<String, Row> info = tableInfo("legacy");
        assertEquals("INTEGER", info.get("totalExp").getString("type"));
        assertEquals("0", info.get("totalExp").getString("dflt_value"));

        test.db.addColumnIfMissing(legacy, totalExp);
        assertEquals(3, tableInfo("legacy").size());
    }

    @Test
    void addColumnIfMissingIsNoOpWhenColumnExists() {
        test.db.createTableIfNotExists(Fixtures.SESSIONS);

        test.db.addColumnIfMissing(Fixtures.SESSIONS, Fixtures.SESSIONS_DATA.notNull());

        assertEquals(3, tableInfo("sessions").size());
    }

    @Test
    void derivesAllSupportedColumnTypes() {
        var table = Table.of("typed");
        table.column("t", String.class);
        table.column("i", Long.class);
        table.column("r", Double.class);
        table.column("b", byte[].class);

        test.db.createTableIfNotExists(table);

        Map<String, Row> info = tableInfo("typed");

        assertEquals("TEXT", info.get("t").getString("type"));
        assertEquals("INTEGER", info.get("i").getString("type"));
        assertEquals("REAL", info.get("r").getString("type"));
        assertEquals("BLOB", info.get("b").getString("type"));
    }

    @Test
    void createTableIfNotExistsAsyncCreatesTable() {
        test.db.createTableIfNotExistsAsync(Fixtures.SESSIONS).join();

        assertEquals(3, tableInfo("sessions").size());
    }

    @Test
    void addColumnIfMissingAsyncAddsColumn() {
        var legacy = Table.of("legacy2");
        test.db.raw("CREATE TABLE legacy2 (uuid TEXT PRIMARY KEY)");

        test.db.addColumnIfMissingAsync(legacy, legacy.column("totalExp", Long.class).defaultValue(0L)).join();

        Map<String, Row> info = tableInfo("legacy2");
        assertTrue(info.containsKey("totalExp"));
        assertFalse(info.containsKey("data"));
    }
}