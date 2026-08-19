package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class TableColumnTest {

    @Test
    void tableExposesItsName() {
        assertEquals("users", Fixtures.USERS.name());
    }

    @Test
    void columnExposesNameAndType() {
        assertEquals("name", Fixtures.USERS_NAME.name());
        assertEquals(String.class, Fixtures.USERS_NAME.type());
    }

    @Test
    void qualifiedNameCombinesTableAndColumn() {
        assertEquals("users.id", Fixtures.USERS_ID.qualifiedName());
        assertEquals("users.active", Fixtures.USERS_ACTIVE.qualifiedName());
    }

    @Test
    void ascOrderIsAscending() {
        assertTrue(Fixtures.USERS_ID.asc().ascending());
    }

    @Test
    void descOrderIsDescending() {
        assertFalse(Fixtures.USERS_ID.desc().ascending());
    }

    @Test
    void orderCarriesItsColumn() {
        assertEquals(Fixtures.USERS_ID, Fixtures.USERS_ID.asc().column());
    }

    @Test
    void primaryKeyFlagIsSetByBuilder() {
        var pk = Fixtures.USERS_ID.primaryKey();

        assertTrue(pk.isPrimaryKey());
        assertFalse(Fixtures.USERS_ID.isPrimaryKey());
    }

    @Test
    void notNullFlagIsSetByBuilder() {
        var required = Fixtures.USERS_NAME.notNull();

        assertTrue(required.isNotNullConstraint());
        assertFalse(Fixtures.USERS_NAME.isNotNullConstraint());
    }

    @Test
    void defaultValueIsCarriedAndAbsentByDefault() {
        var withDefault = Fixtures.SESSIONS_TOTAL_EXP.defaultValue(0L);

        assertEquals(0L, withDefault.defaultValueOrNull());
        assertEquals(null, Fixtures.SESSIONS_TOTAL_EXP.defaultValueOrNull());
    }

    @Test
    void builderMethodsPreserveOtherAttributes() {
        var column = Fixtures.USERS_ID.primaryKey().notNull().defaultValue(0L);

        assertTrue(column.isPrimaryKey());
        assertTrue(column.isNotNullConstraint());
        assertEquals(0L, column.defaultValueOrNull());
        assertEquals("users", column.table().name());
        assertEquals("id", column.name());
        assertEquals(Long.class, column.type());
        assertEquals("users.id", column.qualifiedName());
    }

    @Test
    void builderMethodsDoNotMutateOriginal() {
        var original = Fixtures.USERS_NAME;
        original.primaryKey();
        original.notNull();
        original.defaultValue("x");

        assertFalse(original.isPrimaryKey());
        assertFalse(original.isNotNullConstraint());
        assertEquals(null, original.defaultValueOrNull());
    }

    @Test
    void typeDerivationMapsSupportedTypesToSqliteTypes() {
        assertEquals("TEXT", SqlTypeConverter.columnTypeFor(String.class));
        assertEquals("TEXT", SqlTypeConverter.columnTypeFor(UUID.class));
        assertEquals("TEXT", SqlTypeConverter.columnTypeFor(Instant.class));
        assertEquals("TEXT", SqlTypeConverter.columnTypeFor(TestEnum.class));
        assertEquals("INTEGER", SqlTypeConverter.columnTypeFor(Integer.class));
        assertEquals("INTEGER", SqlTypeConverter.columnTypeFor(Long.class));
        assertEquals("INTEGER", SqlTypeConverter.columnTypeFor(Short.class));
        assertEquals("INTEGER", SqlTypeConverter.columnTypeFor(Byte.class));
        assertEquals("INTEGER", SqlTypeConverter.columnTypeFor(Boolean.class));
        assertEquals("REAL", SqlTypeConverter.columnTypeFor(Float.class));
        assertEquals("REAL", SqlTypeConverter.columnTypeFor(Double.class));
        assertEquals("BLOB", SqlTypeConverter.columnTypeFor(byte[].class));
    }

    enum TestEnum {
        A
    }
}
