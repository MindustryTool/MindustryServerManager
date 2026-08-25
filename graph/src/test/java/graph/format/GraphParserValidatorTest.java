package graph.format;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphParserValidatorTest {

    private static final String MINIMAL = """
            {"version":1,"id":"simple-flow",
             "nodes":[{"id":"ev","type":"event","event":"mindustry.player.join"}]}
            """;

    @Nested
    class Parsing {
        @Test
        void parsesMinimalDocument() {
            var outcome = GraphParser.parse(MINIMAL);
            assertTrue(outcome.ok(), () -> outcome.diagnostics().toString());
            GraphDocument doc = outcome.document();
            assertEquals(1, doc.version());
            assertEquals("simple-flow", doc.id());
            assertEquals(0, doc.revision());
            assertEquals(1, doc.nodes().size());
            assertEquals("event", doc.nodes().get(0).type());
            assertEquals("mindustry.player.join", doc.nodes().get(0).getString("event"));
        }

        @Test
        void rejectsInvalidJson() {
            var outcome = GraphParser.parse("{not json");
            assertFalse(outcome.ok());
            assertTrue(outcome.document() == null);
            assertEquals("E_PARSE", outcome.diagnostics().get(0).code());
        }

        @Test
        void rejectsNonObjectRoot() {
            var outcome = GraphParser.parse("[1,2,3]");
            assertFalse(outcome.ok());
            assertEquals("E_PARSE", outcome.diagnostics().get(0).code());
        }

        @Test
        void reportsUnknownTopLevelFields() {
            String raw = """
                    {"version":1,"id":"flow","nodes":[],"bogus":{"x":1}}
                    """;
            var outcome = GraphParser.parse(raw);
            assertFalse(outcome.ok());
            assertTrue(outcome.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_UNKNOWN_FIELD")
                            && d.message().contains("bogus")));
        }

        @Test
        void missingRequiredFields() {
            var noNodes = GraphParser.parse("{\"version\":1,\"id\":\"flow\"}");
            assertFalse(noNodes.ok());
            assertTrue(noNodes.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_MISSING_FIELD")
                            && d.pointer() != null && d.pointer().equals("nodes")));

            var noId = GraphParser.parse("{\"version\":1,\"nodes\":[]}");
            assertFalse(noId.ok());
            assertTrue(noId.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_MISSING_FIELD") && d.pointer().equals("id")));
        }
    }

    @Nested
    class VersionGate {
        @Test
        void newerVersionRejectedWithFoundAndSupported() {
            String raw = """
                    {"version":2,"id":"future-flow","nodes":[]}
                    """;
            var outcome = GraphParser.parse(raw);
            assertFalse(outcome.ok());
            assertTrue(outcome.document() == null);
            Diagnostic diagnostic = outcome.diagnostics().stream()
                    .filter(d -> d.code().equals("E_VERSION_UNSUPPORTED")).findFirst().orElseThrow();
            assertTrue(diagnostic.message().contains("2"));
            assertTrue(diagnostic.message().contains("[1]"));
        }

        @Test
        void olderVersionRejected() {
            var outcome = GraphParser.parse("{\"version\":0,\"id\":\"old\",\"nodes\":[]}");
            assertFalse(outcome.ok());
            assertTrue(outcome.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_VERSION_UNSUPPORTED")));
        }

        @Test
        void nonIntegerVersionRejected() {
            var outcome = GraphParser.parse("{\"version\":\"1\",\"id\":\"flow\",\"nodes\":[]}");
            assertFalse(outcome.ok());
            assertTrue(outcome.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_INVALID_FIELD") && d.pointer().equals("version")));
        }

        @Test
        void missingVersionRejectedAsMissingField() {
            var outcome = GraphParser.parse("{\"id\":\"flow\",\"nodes\":[]}");
            assertFalse(outcome.ok());
            assertTrue(outcome.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_MISSING_FIELD") && d.pointer().equals("version")));
        }
    }

    @Nested
    class SchemaValidation {
        @Test
        void minimalDocumentIsValid() {
            GraphDocument doc = GraphParser.parse(MINIMAL).document();
            ValidationResult result = SchemaValidator.validate(doc);
            assertTrue(result.ok(), () -> result.diagnostics().toString());
        }

        @Test
        void unknownNodeTypeRejectedWithNodeScope() throws Exception {
            GraphDocument doc = GraphDocument.initial(
                    "flow",
                    List.of(),
                    List.of(GraphNode.of("bad", "quantum-flux"), GraphNode.of("ok", "log")),
                    List.of(),
                    null);
            ValidationResult result = SchemaValidator.validate(doc);
            assertFalse(result.ok());
            Diagnostic error = result.errors().stream()
                    .filter(d -> d.code().equals("E_UNKNOWN_NODE_TYPE")).findFirst().orElseThrow();
            assertEquals("bad", error.nodeId());
            assertEquals("type", error.pointer());
        }

        @Test
        void duplicateNodeIdsAndDanglingEdgesRejected() {
            GraphDocument doc = GraphDocument.initial(
                    "flow",
                    List.of(),
                    List.of(GraphNode.of("dup", "log"), GraphNode.of("dup", "if")),
                    List.of(GraphEdge.of("ghost.out", "dup.exec"),
                            GraphEdge.of("dup.done", "missing.in")),
                    null);
            ValidationResult result = SchemaValidator.validate(doc);
            assertFalse(result.ok());
            assertTrue(result.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_DUPLICATE_NODE_ID") && d.nodeId().equals("dup")));
            assertTrue(result.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_DANGLING_EDGE") && d.nodeId().equals("ghost")));
            assertTrue(result.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_DANGLING_EDGE") && d.nodeId().equals("missing")));
        }

        @Test
        void variableValidationCoversScopeUniquenessAndType() {
            GraphDocument doc = GraphDocument.initial(
                    "flow",
                    List.of(new VariableDecl("count", "server", "Int"),
                            new VariableDecl("count", "LOCAL", "String"),
                            new VariableDecl("weird", "GALAXY", "Int"),
                            new VariableDecl("bad", "GRAPH", "List<")),
                    List.of(),
                    List.of(),
                    null);
            ValidationResult result = SchemaValidator.validate(doc);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("E_DUPLICATE_VARIABLE")));
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("E_UNKNOWN_SCOPE")));
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("E_INVALID_TYPE")));
            assertFalse(result.ok());
        }

        @Test
        void scopedVariablesRequireKey() {
            GraphDocument doc = GraphDocument.initial(
                    "flow",
                    List.of(new VariableDecl("visits", "PLAYER", "Int")),
                    List.of(),
                    List.of(),
                    null);
            ValidationResult result = SchemaValidator.validate(doc);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("E_MISSING_FIELD")));
        }

        @Test
        void invalidGraphIdRejected() {
            GraphDocument doc = GraphDocument.initial(
                    "Bad Id!",
                    List.of(),
                    List.of(),
                    List.of(),
                    null);
            ValidationResult result = SchemaValidator.validate(doc);
            assertFalse(result.ok());
            assertTrue(result.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E_INVALID_IDENTIFIER")));
        }

        @Test
        void vocabularyContainsGenericNodeSet() {
            for (String type : new String[]{"call", "code", "http-get", "transaction",
                    "delay", "await", "schedule", "for-each"}) {
                assertTrue(SchemaValidator.NODE_TYPE_VOCABULARY.contains(type), type);
            }
            assertEquals(33, SchemaValidator.NODE_TYPE_VOCABULARY.size());
        }
    }
}
