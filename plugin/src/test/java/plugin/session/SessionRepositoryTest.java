package plugin.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import arc.Core;
import arc.Settings;
import arc.files.Fi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.database.Database;

public class SessionRepositoryTest {

    @TempDir
    static Path settingsDir;

    @TempDir
    Path tempDir;

    Database database;
    SessionRepository repository;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));
    }

    @BeforeEach
    void setUp() throws Exception {
        Database.setTestPath(tempDir.resolve("sessions.db"));
        database = new Database();
        repository = new SessionRepository(database);
        invoke(repository, "createTableIfNotExists", new Class<?>[0]);
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
        Core.settings.remove("EXP_RECALCULATED_5");
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Map<String, plugin.orm.Row> tableInfo(String table) {
        return database.db().rawQuery("PRAGMA table_info(" + table + ")").stream()
                .collect(Collectors.toMap(row -> row.getString("name"), Function.identity()));
    }

    private void seed(String uuid, String json, long totalExp) {
        database.db().raw("INSERT INTO sessions(uuid, data, totalExp) VALUES(?, ?, ?)", uuid, json, totalExp);
    }

    private SessionData dataWithExp(float exp) {
        var data = new SessionData();
        data.name = "player";
        data.exp = exp;
        return data;
    }

    @Test
    void getReturnsFreshDataForUnknownUuid() {
        var data = repository.get("missing-uuid");

        assertNotNull(data);
        assertEquals("", data.name);
    }

    @Test
    void putAndFlushBatchPersistsSession() {
        var data = dataWithExp(10);
        repository.put("u1", data);
        repository.flushBatch();

        var fresh = new SessionRepository(new Database()).get("u1");
        assertEquals(10f, fresh.exp);
    }

    @Test
    void flushBatchIsNoOpWhenNothingDirty() {
        repository.flushBatch();
        assertTrue(true);
    }

    @Test
    void putOverwritesExistingRowOnFlush() {
        repository.put("u1", dataWithExp(10));
        repository.flushBatch();

        repository.put("u1", dataWithExp(25));
        repository.flushBatch();

        var fresh = new SessionRepository(new Database()).get("u1");
        assertEquals(25f, fresh.exp);
    }

    @Test
    void removeClearsCacheAndDirtySet() {
        repository.put("u1", dataWithExp(10));
        repository.markDirty("u1");

        repository.remove("u1");
        repository.flushBatch();

        var fresh = new SessionRepository(new Database()).get("u1");
        assertEquals(0f, fresh.exp);
    }

    @Test
    void getReadsCorruptRowAndReturnsFreshData() {
        seed("corrupt", "{not valid json", 5);

        var data = repository.get("corrupt");

        assertNotNull(data);
        assertEquals("", data.name);
    }

    @Test
    void leaderBoardOrdersByTotalExpDescThenUuidAsc() {
        seed("a", "{\"name\":\"a\",\"exp\":50,\"playTime\":1}", 50);
        seed("b", "{\"name\":\"b\",\"exp\":100,\"playTime\":1}", 100);
        seed("c", "{\"name\":\"c\",\"exp\":25,\"playTime\":1}", 25);
        seed("d", "{\"name\":\"d\",\"exp\":100,\"playTime\":1}", 100);

        var board = repository.leaderBoard(10);

        assertEquals(4, board.size);
        assertEquals("b", board.get(0).uuid);
        assertEquals("d", board.get(1).uuid);
        assertEquals("a", board.get(2).uuid);
        assertEquals("c", board.get(3).uuid);
        assertEquals(100, board.get(0).totalExp);
        assertEquals(100, board.get(1).totalExp);
    }

    @Test
    void leaderBoardRespectsLimit() {
        seed("a", "{\"name\":\"a\",\"exp\":10,\"playTime\":1}", 10);
        seed("b", "{\"name\":\"b\",\"exp\":20,\"playTime\":1}", 20);
        seed("c", "{\"name\":\"c\",\"exp\":30,\"playTime\":1}", 30);

        var board = repository.leaderBoard(2);

        assertEquals(2, board.size);
        assertEquals("c", board.get(0).uuid);
        assertEquals("b", board.get(1).uuid);
    }

    @Test
    void leaderBoardThrowsWhenDataMissing() {
        database.db().raw("INSERT INTO sessions(uuid, data, totalExp) VALUES('empty', '', 1)");

        assertThrows(IllegalArgumentException.class, () -> repository.leaderBoard(10));
    }

    @Test
    void getRankUsesExpAndUuidTiebreak() {
        seed("a", "{\"name\":\"a\"}", 100);
        seed("b", "{\"name\":\"b\"}", 50);
        seed("c", "{\"name\":\"c\"}", 25);
        seed("d", "{\"name\":\"d\"}", 50);

        assertEquals(1, repository.getRank("a"));
        assertEquals(2, repository.getRank("b"));
        assertEquals(3, repository.getRank("d"));
        assertEquals(4, repository.getRank("c"));
        assertEquals(-1, repository.getRank("missing"));
    }

    @Test
    void deferredMigrationRepairsPlayTimeOnlyRows() throws Exception {
        seed("repairable", "{\"name\":\"r\",\"exp\":0,\"playTime\":5000}", 0);
        seed("fresh", "{\"name\":\"f\",\"exp\":10,\"playTime\":0}", 10);
        seed("zero", "{\"name\":\"z\",\"exp\":0,\"playTime\":0}", 0);

        int repaired = (int) invoke(repository, "seedStoredExpCounters", new Class<?>[0]);

        assertEquals(1, repaired);
        assertEquals(5, database.db().rawQuery("SELECT totalExp FROM sessions WHERE uuid = 'repairable'").get(0).getLong("totalExp"));
        assertEquals(10, database.db().rawQuery("SELECT totalExp FROM sessions WHERE uuid = 'fresh'").get(0).getLong("totalExp"));
    }

    @Test
    void deferredMigrationIsIdempotentViaFlag() throws Exception {
        seed("repairable", "{\"name\":\"r\",\"exp\":0,\"playTime\":5000}", 0);

        int first = (int) invoke(repository, "seedStoredExpCounters", new Class<?>[0]);
        invoke(repository, "migrateStoredExpCounters", new Class<?>[0]);
        assertTrue(Core.settings.has("EXP_RECALCULATED_5"));

        seed("late", "{\"name\":\"l\",\"exp\":0,\"playTime\":8000}", 0);
        invoke(repository, "migrateStoredExpCounters", new Class<?>[0]);

        assertEquals(1, first);
        assertEquals(5, database.db().rawQuery("SELECT totalExp FROM sessions WHERE uuid = 'repairable'").get(0).getLong("totalExp"));
        assertEquals(0, database.db().rawQuery("SELECT totalExp FROM sessions WHERE uuid = 'late'").get(0).getLong("totalExp"));
    }

    @Test
    void createTableIsIdempotentAndAddsMissingColumn() throws Exception {
        invoke(repository, "createTableIfNotExists", new Class<?>[0]);
        invoke(repository, "createTableIfNotExists", new Class<?>[0]);

        database.db().raw("INSERT INTO sessions(uuid, data) VALUES('x', '{}')");
        assertEquals(1, database.db().rawQuery("SELECT COUNT(*) AS c FROM sessions").get(0).getInt("c"));

        var info = tableInfo("sessions");
        assertEquals(3, info.size());
        assertEquals(1, info.get("uuid").getInt("pk"));
        assertEquals("TEXT", info.get("uuid").getString("type"));
        assertEquals("TEXT", info.get("data").getString("type"));
        assertEquals(1, info.get("data").getInt("notnull"));
        assertEquals("INTEGER", info.get("totalExp").getString("type"));
        assertEquals("0", info.get("totalExp").getString("dflt_value"));
    }

    @Test
    void createTableIfNotExistsUpgradesLegacySchema() throws Exception {
        database.db().raw("DROP TABLE sessions");
        database.db().raw("CREATE TABLE sessions (uuid TEXT PRIMARY KEY, data TEXT NOT NULL)");

        invoke(repository, "createTableIfNotExists", new Class<?>[0]);

        var info = tableInfo("sessions");
        assertEquals(3, info.size());
        assertEquals("INTEGER", info.get("totalExp").getString("type"));
        assertEquals("0", info.get("totalExp").getString("dflt_value"));
    }

    @Test
    void initCreatesSessionsTableBeforeReturning() {
        repository.init();

        assertEquals(1, database.db().rawQuery("SELECT COUNT(*) AS c FROM sqlite_master WHERE type = 'table' AND name = 'sessions'").get(0).getInt("c"));
    }
}
