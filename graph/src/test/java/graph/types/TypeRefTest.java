package graph.types;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeRefTest {

    @Nested
    class Factories {
        @Test
        void primitiveConstants() {
            assertEquals("Int", TypeRef.INT.base());
            assertTrue(TypeRef.INT.isPrimitive());
            assertTrue(TypeRef.STRING.isPrimitive());
            assertFalse(TypeRef.of("Player").isPrimitive());
            assertFalse(TypeRef.INT.isNullable());
        }

        @Test
        void listCarriesElement() {
            TypeRef t = TypeRef.list(TypeRef.of("Player"));
            assertTrue(t.isList());
            assertEquals(1, t.params().size());
            assertEquals(TypeRef.of("Player"), t.params().get(0));
        }

        @Test
        void mapCarriesKeyAndValue() {
            TypeRef t = TypeRef.map(TypeRef.STRING, TypeRef.INT);
            assertTrue(t.isMap());
            assertEquals(2, t.params().size());
            assertEquals(TypeRef.STRING, t.params().get(0));
            assertEquals(TypeRef.INT, t.params().get(1));
        }

        @Test
        void optionalAndFuture() {
            assertTrue(TypeRef.optional(TypeRef.INT).isOptional());
            assertTrue(TypeRef.future(TypeRef.of("Player")).isFuture());
        }

        @Test
        void nullabilityIsSeparateInstance() {
            TypeRef base = TypeRef.of("Player");
            TypeRef nullable = base.asNullable();
            assertNotEquals(base, nullable);
            assertTrue(nullable.isNullable());
            assertFalse(base.isNullable());
            assertEquals(base, nullable.asNonNull());
            assertEquals(base, base.asNullable().asNullable().asNonNull());
        }

        @Test
        void rejectsInvalidBaseName() {
            assertThrows(IllegalArgumentException.class, () -> TypeRef.of(""));
            assertThrows(IllegalArgumentException.class, () -> TypeRef.of("9Bad"));
            assertThrows(IllegalArgumentException.class, () -> TypeRef.of("Has Space"));
        }
    }

    @Nested
    class Parsing {
        @Test
        void parsesPlainNames() {
            assertEquals(TypeRef.of("Player"), TypeRef.parse("Player"));
            assertEquals(TypeRef.INT, TypeRef.parse("Int"));
        }

        @Test
        void parsesGenerics() {
            assertEquals(TypeRef.list(TypeRef.STRING), TypeRef.parse("List<String>"));
            assertEquals(
                    TypeRef.map(TypeRef.STRING, TypeRef.INT),
                    TypeRef.parse("Map<String, Int>"));
            assertEquals(
                    TypeRef.optional(TypeRef.list(TypeRef.of("Unit"))),
                    TypeRef.parse("Optional<List<Unit>>"));
        }

        @Test
        void parsesNullabilitySuffix() {
            TypeRef parsed = TypeRef.parse("Player?");
            assertTrue(parsed.isNullable());
            assertEquals(TypeRef.of("Player").asNullable(), parsed);

            TypeRef nested = TypeRef.parse("List<Player?>");
            assertFalse(nested.isNullable());
            assertTrue(nested.params().get(0).isNullable());
        }

        @Test
        void toleratesWhitespace() {
            assertEquals(
                    TypeRef.map(TypeRef.STRING, TypeRef.INT).asNullable(),
                    TypeRef.parse("  Map < String , Int > ? "));
        }

        @Test
        void rejectsMalformedInput() {
            assertThrows(IllegalArgumentException.class, () -> TypeRef.parse(""));
            assertThrows(IllegalArgumentException.class, () -> TypeRef.parse("List<"));
            assertThrows(IllegalArgumentException.class, () -> TypeRef.parse("List<String"));
            assertThrows(IllegalArgumentException.class, () -> TypeRef.parse("List<>"));
            assertThrows(IllegalArgumentException.class, () -> TypeRef.parse("<String>"));
            assertThrows(IllegalArgumentException.class, () -> TypeRef.parse("Map<String>"));
            assertThrows(IllegalArgumentException.class, () -> TypeRef.parse("Player extra"));
            assertThrows(IllegalArgumentException.class, () -> TypeRef.parse("List<String>,"));
            assertNull(null);
        }
    }

    @Nested
    class Printing {
        @Test
        void printsPrimitivesAndNames() {
            assertEquals("Player", TypeRef.of("Player").print());
            assertEquals("Int", TypeRef.INT.print());
        }

        @Test
        void printsGenericsAndNullability() {
            assertEquals("List<Player>", TypeRef.list(TypeRef.of("Player")).print());
            assertEquals("Map<String, Int>", TypeRef.map(TypeRef.STRING, TypeRef.INT).print());
            assertEquals("Player?", TypeRef.of("Player").asNullable().print());
            assertEquals("Future<List<Unit>?>",
                    TypeRef.future(TypeRef.list(TypeRef.of("Unit")).asNullable()).print());
        }

        @Test
        void printParseRoundTrip() {
            TypeRef[] samples = {
                    TypeRef.INT,
                    TypeRef.of("Building"),
                    TypeRef.list(TypeRef.of("Player")),
                    TypeRef.map(TypeRef.STRING, TypeRef.list(TypeRef.of("Unit"))),
                    TypeRef.optional(TypeRef.future(TypeRef.of("Bullet")).asNullable()),
                    TypeRef.set(TypeRef.of("Tile")).asNullable()
            };
            for (TypeRef sample : samples) {
                assertEquals(sample, TypeRef.parse(sample.print()), "round trip of " + sample.print());
            }
        }
    }

    @Nested
    class Equality {
        @Test
        void structuralEqualityIncludingParams() {
            assertEquals(TypeRef.list(TypeRef.INT), TypeRef.parse("List<Int>"));
            assertNotEquals(TypeRef.list(TypeRef.INT), TypeRef.list(TypeRef.LONG));
            assertNotEquals(TypeRef.list(TypeRef.INT), TypeRef.set(TypeRef.INT));
            assertNotEquals(TypeRef.map(TypeRef.STRING, TypeRef.INT), TypeRef.list(TypeRef.INT));
        }

        @Test
        void hashCodeMatchesEquality() {
            assertEquals(TypeRef.list(TypeRef.INT).hashCode(), TypeRef.parse("List<Int>").hashCode());
        }

        @Test
        void helpersClassifyCorrectly() {
            assertFalse(TypeRef.list(TypeRef.INT).isPrimitive());
            assertTrue(TypeRef.list(TypeRef.INT).isGenericContainer());
            assertTrue(TypeRef.set(TypeRef.INT).isSet());
            assertFalse(TypeRef.set(TypeRef.INT).isList());
        }
    }
}
