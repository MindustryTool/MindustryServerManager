package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class SqlTypeConverterTest {

    private enum Mood {
        HAPPY, SAD
    }

    @Test
    void integerConvertsToLong() {
        assertEquals(42L, SqlTypeConverter.convert(42, Long.class));
    }

    @Test
    void stringConvertsToLong() {
        assertEquals(42L, SqlTypeConverter.convert("42", Long.class));
    }

    @Test
    void integerConvertsToBoolean() {
        assertEquals(Boolean.TRUE, SqlTypeConverter.convert(1, Boolean.class));
        assertEquals(Boolean.FALSE, SqlTypeConverter.convert(0, Boolean.class));
    }

    @Test
    void booleanConvertsToBoolean() {
        assertEquals(Boolean.TRUE, SqlTypeConverter.convert(true, Boolean.class));
    }

    @Test
    void integerConvertsToShortAndByte() {
        assertEquals(Short.valueOf((short) 7), SqlTypeConverter.convert(7, Short.class));
        assertEquals(Byte.valueOf((byte) 7), SqlTypeConverter.convert(7, Byte.class));
    }

    @Test
    void numberConvertsToDoubleAndFloat() {
        assertEquals(1.5d, SqlTypeConverter.convert(1.5, Double.class));
        assertEquals(1.5f, SqlTypeConverter.convert(1.5, Float.class));
    }

    @Test
    void numberConvertsToString() {
        assertEquals("42", SqlTypeConverter.convert(42, String.class));
    }

    @Test
    void stringConvertsToUuid() {
        UUID uuid = UUID.randomUUID();
        assertEquals(uuid, SqlTypeConverter.convert(uuid.toString(), UUID.class));
    }

    @Test
    void stringConvertsToInstant() {
        Instant instant = Instant.parse("2026-08-19T12:00:00Z");
        assertEquals(instant, SqlTypeConverter.convert("2026-08-19T12:00:00Z", Instant.class));
    }

    @Test
    void stringConvertsToEnum() {
        assertEquals(Mood.HAPPY, SqlTypeConverter.convert("HAPPY", Mood.class));
    }

    @Test
    void byteArrayPassesThrough() {
        byte[] bytes = { 1, 2, 3 };
        assertArrayEquals(bytes, SqlTypeConverter.convert(bytes, byte[].class));
    }

    @Test
    void nullConvertsToNull() {
        assertNull(SqlTypeConverter.convert(null, String.class));
        assertNull(SqlTypeConverter.convert(null, Long.class));
        assertNull(SqlTypeConverter.convert(null, UUID.class));
    }

    @Test
    void rawPassesThroughWhenAlreadyAssignable() {
        Object value = new Object();
        assertEquals(value, SqlTypeConverter.convert(value, Object.class));
    }

    @Test
    void unconvertibleValueThrows() {
        assertThrows(OrmException.class, () -> SqlTypeConverter.convert("not-a-number", Integer.class));
    }

    @Test
    void invalidUuidThrows() {
        assertThrows(OrmException.class, () -> SqlTypeConverter.convert("nope", UUID.class));
    }

    @Test
    void invalidInstantThrows() {
        assertThrows(OrmException.class, () -> SqlTypeConverter.convert("nope", Instant.class));
    }
}
