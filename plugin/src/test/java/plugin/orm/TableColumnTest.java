package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
