package plugin.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class ConditionTest {

    @Test
    void rendersEqWithParameter() {
        List<Object> params = new ArrayList<>();
        assertEquals("users.id = ?", Render.condition(Fixtures.USERS_ID.eq(5L), params));
        assertEquals(List.of(5L), params);
    }

    @Test
    void rendersNeWithParameter() {
        List<Object> params = new ArrayList<>();
        assertEquals("users.id <> ?", Render.condition(Fixtures.USERS_ID.ne(5L), params));
        assertEquals(List.of(5L), params);
    }

    @Test
    void rendersGtGteLtLte() {
        assertEquals("users.id > ?", Render.condition(Fixtures.USERS_ID.gt(1L)));
        assertEquals("users.id >= ?", Render.condition(Fixtures.USERS_ID.gte(1L)));
        assertEquals("users.id < ?", Render.condition(Fixtures.USERS_ID.lt(1L)));
        assertEquals("users.id <= ?", Render.condition(Fixtures.USERS_ID.lte(1L)));
    }

    @Test
    void rendersInWithParameters() {
        List<Object> params = new ArrayList<>();
        assertEquals("users.id IN (?, ?, ?)", Render.condition(Fixtures.USERS_ID.in(1L, 2L, 3L), params));
        assertEquals(List.of(1L, 2L, 3L), params);
    }

    @Test
    void rendersNotInWithParameters() {
        List<Object> params = new ArrayList<>();
        assertEquals("users.id NOT IN (?, ?)", Render.condition(Fixtures.USERS_ID.notIn(1L, 2L), params));
        assertEquals(List.of(1L, 2L), params);
    }

    @Test
    void emptyInRendersAlwaysFalse() {
        assertEquals("1 = 0", Render.condition(Fixtures.USERS_ID.in(List.of())));
    }

    @Test
    void emptyNotInRendersAlwaysTrue() {
        assertEquals("1 = 1", Render.condition(Fixtures.USERS_ID.notIn(List.of())));
    }

    @Test
    void rendersLikeWithPatternParameter() {
        List<Object> params = new ArrayList<>();
        assertEquals("users.name LIKE ?", Render.condition(Fixtures.USERS_NAME.like("H%"), params));
        assertEquals(List.of("H%"), params);
    }

    @Test
    void rendersIsNull() {
        assertEquals("users.name IS NULL", Render.condition(Fixtures.USERS_NAME.isNull()));
    }

    @Test
    void rendersIsNotNull() {
        assertEquals("users.name IS NOT NULL", Render.condition(Fixtures.USERS_NAME.isNotNull()));
    }

    @Test
    void eqNullRendersIsNull() {
        assertEquals("users.name IS NULL", Render.condition(Fixtures.USERS_NAME.eq(null)));
    }

    @Test
    void neNullRendersIsNotNull() {
        assertEquals("users.name IS NOT NULL", Render.condition(Fixtures.USERS_NAME.ne(null)));
    }

    @Test
    void andCompositionRendersWithAnd() {
        List<Object> params = new ArrayList<>();
        assertEquals("users.active = ? AND users.id > ?",
                Render.condition(Fixtures.USERS_ACTIVE.eq(true).and(Fixtures.USERS_ID.gt(10L)), params));
        assertEquals(List.of(true, 10L), params);
    }

    @Test
    void orCompositionRendersWithOr() {
        assertEquals("users.id = ? OR users.id = ?",
                Render.condition(Fixtures.USERS_ID.eq(1L).or(Fixtures.USERS_ID.eq(2L))));
    }

    @Test
    void notRendersParenthesized() {
        List<Object> params = new ArrayList<>();
        assertEquals("NOT (users.active = ?)", Render.condition(Fixtures.USERS_ACTIVE.eq(true).not(), params));
        assertEquals(List.of(true), params);
    }

    @Test
    void nestedOrInsideAndGetsParenthesized() {
        assertEquals("users.active = ? AND (users.id > ? OR users.name IS NULL)",
                Render.condition(Fixtures.USERS_ACTIVE.eq(true)
                        .and(Fixtures.USERS_ID.gt(10L).or(Fixtures.USERS_NAME.isNull()))));
    }

    @Test
    void flatAndChainHasNoRedundantParens() {
        assertEquals("users.id = ? AND users.id = ? AND users.id = ?", Render.condition(
                Fixtures.USERS_ID.eq(1L).and(Fixtures.USERS_ID.eq(2L)).and(Fixtures.USERS_ID.eq(3L))));
    }

    @Test
    void andInsideOrGetsParenthesized() {
        assertEquals("(users.id = ? AND users.active = ?) OR users.name IS NULL", Render.condition(
                Fixtures.USERS_ID.eq(1L).and(Fixtures.USERS_ACTIVE.eq(true)).or(Fixtures.USERS_NAME.isNull())));
    }

    @Test
    void notOfCompoundKeepsGrouping() {
        assertEquals("NOT (users.id = ? OR users.id = ?)",
                Render.condition(Fixtures.USERS_ID.eq(1L).or(Fixtures.USERS_ID.eq(2L)).not()));
    }

    @Test
    void unknownColumnThrowsOnMissingMapper() {
        assertThrows(OrmException.class, () -> SQLiteDatabase.builder().path("x.db").build().mapperFor(Integer.class));
    }
}
