package graph.registry;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import graph.types.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescriptorTest {

    @Nested
    class ThreadRequirementTest {
        @Test
        void parsesKnownNames() {
            assertEquals(ThreadRequirement.MAIN_THREAD, ThreadRequirement.parse("MAIN_THREAD"));
            assertEquals(ThreadRequirement.ASYNC, ThreadRequirement.parse("ASYNC"));
            assertEquals(ThreadRequirement.PURE, ThreadRequirement.parse("PURE"));
            assertEquals(ThreadRequirement.READ_ONLY, ThreadRequirement.parse("READ_ONLY"));
            assertEquals(ThreadRequirement.UNSAFE, ThreadRequirement.parse("UNSAFE"));
        }

        @Test
        void rejectsUnknownNames() {
            assertThrows(IllegalArgumentException.class, () -> ThreadRequirement.parse("SOMETIMES"));
            assertThrows(IllegalArgumentException.class, () -> ThreadRequirement.parse(""));
            assertFalse(ThreadRequirement.isValid("WHENEVER"));
            assertTrue(ThreadRequirement.isValid("UNSAFE"));
        }

        @Test
        void mainThreadClassification() {
            assertTrue(ThreadRequirement.MAIN_THREAD.runsOnMainThread());
            assertTrue(ThreadRequirement.PURE.runsOnMainThread());
            assertTrue(ThreadRequirement.READ_ONLY.runsOnMainThread());
            assertFalse(ThreadRequirement.ASYNC.runsOnMainThread());
            assertFalse(ThreadRequirement.UNSAFE.runsOnMainThread());
        }
    }

    @Nested
    class OverloadHash {
        @Test
        void signatureEncodesParamsAndReturn() {
            Overload overload = Overload.of(TypeRef.INT,
                    new ParamDescriptor("player", TypeRef.of("Player")),
                    new ParamDescriptor("text", TypeRef.STRING));
            assertEquals("(Player,String):Int", overload.signature());
        }

        @Test
        void hashIsStableAndOrderSensitive() {
            Overload a = Overload.of(TypeRef.INT,
                    new ParamDescriptor("x", TypeRef.INT),
                    new ParamDescriptor("y", TypeRef.STRING));
            Overload b = Overload.of(TypeRef.INT,
                    new ParamDescriptor("y", TypeRef.STRING),
                    new ParamDescriptor("x", TypeRef.INT));
            Overload a2 = Overload.of(TypeRef.INT,
                    new ParamDescriptor("x", TypeRef.INT),
                    new ParamDescriptor("y", TypeRef.STRING));
            assertEquals(a.hash(), a2.hash());
            assertNotEquals(a.hash(), b.hash());
            assertEquals(12, a.hash().length());
        }

        @Test
        void nullabilityChangesSignature() {
            Overload nullable = Overload.of(TypeRef.INT, new ParamDescriptor("p",
                    TypeRef.of("Player").asNullable()));
            Overload strict = Overload.of(TypeRef.INT, new ParamDescriptor("p", TypeRef.of("Player")));
            assertNotEquals(nullable.hash(), strict.hash());
        }

        @Test
        void arityHelper() {
            assertEquals(2, Overload.of(TypeRef.INT,
                    new ParamDescriptor("a", TypeRef.INT),
                    new ParamDescriptor("b", TypeRef.INT)).arity());
            assertEquals(0, Overload.of(TypeRef.INT).arity());
        }
    }

    @Nested
    class FunctionDescriptors {
        private FunctionDescriptor validFunction() {
            return FunctionDescriptor.builder("mindustry.player.sendMessage")
                    .category("Communication")
                    .ownerType("Player")
                    .overload(Overload.of(TypeRef.BOOLEAN,
                            new ParamDescriptor("player", TypeRef.of("Player")),
                            new ParamDescriptor("message", TypeRef.STRING)))
                    .build();
        }

        @Test
        void buildsDefaults() {
            FunctionDescriptor descriptor = validFunction();
            assertEquals("sendMessage", descriptor.displayName());
            assertEquals("", descriptor.sinceVersion());
            assertFalse(descriptor.advanced());
            assertFalse(descriptor.deprecated());
            assertTrue(descriptor.codegenSafe());
            assertEquals(1, descriptor.overloads().size());
            assertEquals(ThreadRequirement.MAIN_THREAD, descriptor.threadRequirement());
        }

        @Test
        void explicitDisplayNameWins() {
            FunctionDescriptor descriptor = FunctionDescriptor
                    .builder("a.b.c").displayName("Fancy Name")
                    .overload(Overload.of(TypeRef.INT)).build();
            assertEquals("Fancy Name", descriptor.displayName());
        }

        @Test
        void requiresAtLeastOneOverload() {
            assertThrows(IllegalArgumentException.class,
                    () -> FunctionDescriptor.builder("x.y.z").build());
        }

        @Test
        void rejectsMalformedIds() {
            assertThrows(IllegalArgumentException.class,
                    () -> FunctionDescriptor.builder("Bad ID").overload(Overload.of(TypeRef.INT)).build());
            assertThrows(IllegalArgumentException.class,
                    () -> FunctionDescriptor.builder("").overload(Overload.of(TypeRef.INT)).build());
        }

        @Test
        void aliasesAreCopiedAndImmutable() {
            FunctionDescriptor descriptor = FunctionDescriptor.builder("a.b")
                    .alias("one").alias("two")
                    .overload(Overload.of(TypeRef.INT)).build();
            assertEquals(List.of("one", "two"), descriptor.aliases());
            assertThrows(UnsupportedOperationException.class, () -> descriptor.aliases().add("three"));
        }
    }

    @Nested
    class OtherDescriptors {
        @Test
        void propertyRequiresValidName() {
            assertThrows(IllegalArgumentException.class, () -> new PropertyDescriptor(
                    "player.team", "has space", TypeRef.of("Player"), TypeRef.of("Team"),
                    false, ThreadRequirement.MAIN_THREAD, ""));
            PropertyDescriptor ok = new PropertyDescriptor(
                    "player.team", "team", TypeRef.of("Player"), TypeRef.of("Team"),
                    true, ThreadRequirement.MAIN_THREAD, "Team of the player");
            assertEquals("team", ok.property());
            assertTrue(ok.writable());
        }

        @Test
        void eventDefaultsDisplayNameToId() {
            EventDescriptor event = new EventDescriptor("mindustry.player.join", null,
                    List.of(new ParamDescriptor("player", TypeRef.of("Player"))), "", "");
            assertEquals("mindustry.player.join", event.displayName());
            assertEquals(1, event.payload().size());
        }

        @Test
        void typeDescriptorValidatesBaseName() {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeDescriptor.mindustry("9invalid", ""));
            assertEquals("mindustry", TypeDescriptor.mindustry("Tile", "").kind());
        }
    }
}
