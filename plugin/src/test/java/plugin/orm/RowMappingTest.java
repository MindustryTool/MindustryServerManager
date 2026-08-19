package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import plugin.orm.table.Column;
import plugin.orm.table.Table;

public class RowMappingTest {

    private enum Role {
        ADMIN, PLAYER
    }

    private static final Table<Object> TYPES = Table.of("types");
    private static final Column<String> C_STRING = TYPES.column("c_string", String.class);
    private static final Column<Integer> C_INT = TYPES.column("c_int", Integer.class);
    private static final Column<Long> C_LONG = TYPES.column("c_long", Long.class);
    private static final Column<Short> C_SHORT = TYPES.column("c_short", Short.class);
    private static final Column<Byte> C_BYTE = TYPES.column("c_byte", Byte.class);
    private static final Column<Boolean> C_BOOL = TYPES.column("c_bool", Boolean.class);
    private static final Column<Float> C_FLOAT = TYPES.column("c_float", Float.class);
    private static final Column<Double> C_DOUBLE = TYPES.column("c_double", Double.class);
    private static final Column<byte[]> C_BLOB = TYPES.column("c_blob", byte[].class);
    private static final Column<UUID> C_UUID = TYPES.column("c_uuid", UUID.class);
    private static final Column<Instant> C_INSTANT = TYPES.column("c_instant", Instant.class);
    private static final Column<Role> C_ENUM = TYPES.column("c_enum", Role.class);

    @TempDir
    Path tempDir;

    TestDatabase test;

    @BeforeEach
    void setUp() {
        test = TestDatabase.create(tempDir);
        test.db.raw("CREATE TABLE IF NOT EXISTS types ("
                + "c_string TEXT, c_int INTEGER, c_long INTEGER, c_short INTEGER, c_byte INTEGER, "
                + "c_bool INTEGER, c_float REAL, c_double REAL, c_blob BLOB, c_uuid TEXT, c_instant TEXT, c_enum TEXT)");
    }

    @AfterEach
    void tearDown() {
        test.close();
    }

    @Test
    void allSupportedTypesRoundTrip() {
        UUID uuid = UUID.randomUUID();
        Instant instant = Instant.parse("2026-08-19T12:00:00Z");
        byte[] blob = { 1, 2, 3 };

        test.db.insert(TYPES)
                .set(C_STRING, "hello")
                .set(C_INT, 42)
                .set(C_LONG, 9999999999L)
                .set(C_SHORT, (short) 7)
                .set(C_BYTE, (byte) 3)
                .set(C_BOOL, true)
                .set(C_FLOAT, 1.5f)
                .set(C_DOUBLE, 2.25d)
                .set(C_BLOB, blob)
                .set(C_UUID, uuid)
                .set(C_INSTANT, instant)
                .set(C_ENUM, Role.ADMIN)
                .execute();

        var row = test.db.select().from(TYPES).fetchOne().orElseThrow();

        assertEquals("hello", row.get(C_STRING));
        assertEquals(42, row.get(C_INT));
        assertEquals(9999999999L, row.get(C_LONG));
        assertEquals(Short.valueOf((short) 7), row.get(C_SHORT));
        assertEquals(Byte.valueOf((byte) 3), row.get(C_BYTE));
        assertEquals(Boolean.TRUE, row.get(C_BOOL));
        assertEquals(1.5f, row.get(C_FLOAT));
        assertEquals(2.25d, row.get(C_DOUBLE));
        assertArrayEquals(blob, row.get(C_BLOB));
        assertEquals(uuid, row.get(C_UUID));
        assertEquals(instant, row.get(C_INSTANT));
        assertEquals(Role.ADMIN, row.get(C_ENUM));
    }

    @Test
    void booleanFalseRoundTripsAsZero() {
        test.db.insert(TYPES).set(C_BOOL, false).execute();
        var row = test.db.select(C_BOOL).from(TYPES).fetchOne().orElseThrow();
        assertEquals(Boolean.FALSE, row.get(C_BOOL));
        assertEquals(0, row.getObject("c_bool"));
    }

    @Test
    void nullValuesRoundTripAsNull() {
        test.db.insert(TYPES).set(C_STRING, "x").execute();

        var row = test.db.select().from(TYPES).fetchOne().orElseThrow();
        assertNull(row.getObject("c_int"));
        assertNull(row.get(C_LONG));
        assertNull(row.get(C_UUID));
        assertNull(row.get(C_INSTANT));
        assertNull(row.get(C_ENUM));
        assertNull(row.get(C_BOOL));
    }

    @Test
    void customRowMapperMapsTypedFetch() {
        test.db.insert(TYPES).set(C_STRING, "a").set(C_LONG, 5L).execute();
        test.db.insert(TYPES).set(C_STRING, "b").set(C_LONG, 6L).execute();

        test.db.registerMapper(Record.class, rs -> new Record(rs.getString("c_string"), rs.getLong("c_long")));

        List<Record> records = test.db.select(C_STRING, C_LONG).from(TYPES).orderBy(C_STRING.asc())
                .fetch(Record.class);

        assertEquals(List.of(new Record("a", 5L), new Record("b", 6L)), records);
    }

    @Test
    void missingMapperFailsWithClearError() {
        test.db.insert(TYPES).set(C_STRING, "a").execute();

        var error = assertThrows(OrmException.class,
                () -> test.db.select().from(TYPES).fetch(Record.class));
        assertTrue(error.getMessage().contains("No mapper registered for"));
    }

    @Test
    void typedFetchOneUsesRegisteredMapper() {
        test.db.registerMapper(Record.class, rs -> new Record(rs.getString("c_string"), rs.getLong("c_long")));
        test.db.insert(TYPES).set(C_STRING, "only").set(C_LONG, 1L).execute();

        var record = test.db.select(C_STRING, C_LONG).from(TYPES).fetchOne(Record.class);
        assertTrue(record.isPresent());
        assertEquals(new Record("only", 1L), record.get());
    }

    @Test
    void unknownColumnThrowsClearError() {
        test.db.insert(TYPES).set(C_STRING, "x").execute();
        var row = test.db.select(C_STRING).from(TYPES).fetchOne().orElseThrow();
        var error = assertThrows(OrmException.class, () -> row.getString("nope"));
        assertTrue(error.getMessage().contains("nope"));
    }

    @Test
    void rowAccessorsConvertRawValues() {
        test.db.insert(TYPES).set(C_STRING, "42").set(C_INT, 7).execute();

        var row = test.db.select().from(TYPES).fetchOne().orElseThrow();
        assertEquals(42L, row.getLong("c_string"));
        assertEquals("7", row.getString("c_int"));
    }

    private record Record(String name, long exp) {
    }
}
