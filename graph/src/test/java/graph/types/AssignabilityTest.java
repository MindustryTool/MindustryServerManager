package graph.types;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignabilityTest {

    @Nested
    class IdenticalAndNullability {
        @Test
        void identicalTypesAssignable() {
            assertTrue(Assignability.isAssignable(TypeRef.of("Player"), TypeRef.of("Player")));
            assertEquals(Assignability.Relation.IDENTICAL,
                    Assignability.relation(TypeRef.INT, TypeRef.INT));
        }

        @Test
        void nonNullableIntoNullableAllowed() {
            assertTrue(Assignability.isAssignable(TypeRef.of("Player"),
                    TypeRef.of("Player").asNullable()));
        }

        @Test
        void nullableIntoNonNullableRejected() {
            assertFalse(Assignability.isAssignable(TypeRef.of("Player").asNullable(),
                    TypeRef.of("Player")));
            assertEquals(Assignability.Relation.NULLABILITY_MISMATCH,
                    Assignability.relation(TypeRef.list(TypeRef.INT).asNullable(),
                            TypeRef.list(TypeRef.INT)));
        }
    }

    @Nested
    class NumericWidening {
        @Test
        void wideningChain() {
            for (String from : new String[]{"Byte", "Int", "Long", "Float"}) {
                for (String to : new String[]{"Int", "Long", "Float", "Double"}) {
                    if (NUMERIC.indexOf(from) < NUMERIC.indexOf(to)) {
                        assertTrue(Assignability.isAssignable(TypeRef.of(from), TypeRef.of(to)),
                                from + " -> " + to);
                        assertEquals(Assignability.Relation.WIDENING,
                                Assignability.relation(TypeRef.of(from), TypeRef.of(to)));
                    }
                }
            }
        }

        @Test
        void narrowingRejected() {
            assertFalse(Assignability.isAssignable(TypeRef.DOUBLE, TypeRef.INT));
            assertFalse(Assignability.isAssignable(TypeRef.LONG, TypeRef.BYTE));
        }

        @Test
        void booleanNotNumeric() {
            assertFalse(Assignability.isAssignable(TypeRef.BOOLEAN, TypeRef.INT));
            assertFalse(Assignability.isAssignable(TypeRef.INT, TypeRef.BOOLEAN));
        }
    }

    private static final java.util.List<String> NUMERIC =
            java.util.List.of("Byte", "Int", "Long", "Float", "Double");

    @Nested
    class StringConversions {
        @Test
        void stringParsesToPrimitives() {
            for (String target : new String[]{"Int", "Long", "Float", "Double", "Boolean"}) {
                assertTrue(Assignability.isAssignable(TypeRef.STRING, TypeRef.of(target)),
                        "String -> " + target);
                assertEquals(Assignability.Relation.STRING_TO_NUMBER,
                        Assignability.relation(TypeRef.STRING, TypeRef.of(target)));
            }
        }

        @Test
        void primitivesToStringify() {
            for (String source : new String[]{"Byte", "Int", "Long", "Float", "Double", "Boolean"}) {
                assertTrue(Assignability.isAssignable(TypeRef.of(source), TypeRef.STRING),
                        source + " -> String");
            }
        }

        @Test
        void stringToStringIsIdentical() {
            assertEquals(Assignability.Relation.IDENTICAL,
                    Assignability.relation(TypeRef.STRING, TypeRef.STRING));
        }
    }

    @Nested
    class Containers {
        @Test
        void covariantElements() {
            assertTrue(Assignability.isAssignable(
                    TypeRef.list(TypeRef.INT),
                    TypeRef.list(TypeRef.DOUBLE)));
            assertTrue(Assignability.isAssignable(
                    TypeRef.map(TypeRef.STRING, TypeRef.INT),
                    TypeRef.map(TypeRef.STRING, TypeRef.LONG)));
        }

        @Test
        void differentContainersRejected() {
            assertFalse(Assignability.isAssignable(
                    TypeRef.list(TypeRef.INT),
                    TypeRef.set(TypeRef.INT)));
        }

        @Test
        void containerNullabilityRespected() {
            assertFalse(Assignability.isAssignable(
                    TypeRef.list(TypeRef.of("Player")).asNullable(),
                    TypeRef.list(TypeRef.of("Player"))));
            assertTrue(Assignability.isAssignable(
                    TypeRef.list(TypeRef.of("Player").asNullable()),
                    TypeRef.list(TypeRef.of("Player").asNullable())));
        }

        @Test
        void unknownBasesRequireExactMatch() {
            assertTrue(Assignability.isAssignable(TypeRef.of("Unit"), TypeRef.of("Unit")));
            assertFalse(Assignability.isAssignable(TypeRef.of("Unit"), TypeRef.of("Building")));
            assertFalse(Assignability.isAssignable(TypeRef.of("Tile"), TypeRef.of("Team")));
        }
    }

    @Nested
    class ExecPorts {
        private final TypeRef exec = TypeRef.of(Assignability.EXEC_BASE);

        @Test
        void execConnectsToExecOnly() {
            assertTrue(Assignability.isAssignable(exec, exec));
            assertEquals(Assignability.Relation.EXEC, Assignability.relation(exec, exec));
            assertFalse(Assignability.isAssignable(exec, TypeRef.INT));
            assertFalse(Assignability.isAssignable(TypeRef.STRING, exec));
        }
    }
}
